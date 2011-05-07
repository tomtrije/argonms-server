/*
 * ArgonMS MapleStory server emulator written in Java
 * Copyright (C) 2011  GoldenKevin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package argonms.game.handler;

import argonms.character.Player;
import argonms.character.inventory.Inventory.InventoryType;
import argonms.character.inventory.InventorySlot;
import argonms.character.inventory.InventoryTools;
import argonms.character.inventory.InventoryTools.WeaponClass;
import argonms.character.skill.PlayerStatusEffectValues.PlayerStatusEffect;
import argonms.character.skill.SkillTools;
import argonms.character.skill.Skills;
import argonms.character.skill.PlayerStatusEffectValues;
import argonms.game.GameClient;
import argonms.loading.skill.SkillDataLoader;
import argonms.loading.skill.PlayerSkillEffectsData;
import argonms.loading.skill.SkillStats;
import argonms.map.Element;
import argonms.map.GameMap;
import argonms.map.MapEntity.EntityType;
import argonms.map.MonsterStatusEffectTools;
import argonms.map.entity.ItemDrop;
import argonms.map.entity.Mob;
import argonms.net.external.ClientSendOps;
import argonms.net.external.CommonPackets;
import argonms.net.external.RemoteClient;
import argonms.tools.Rng;
import argonms.tools.Timer;
import argonms.tools.input.LittleEndianReader;
import argonms.tools.output.LittleEndianByteArrayWriter;
import argonms.tools.output.LittleEndianWriter;
import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 *
 * @author GoldenKevin
 */
public class DealDamageHandler {
	private enum AttackType { MELEE, RANGED, MAGIC, SUMMON, CHARGE }

	public static void handleMeleeAttack(LittleEndianReader packet, RemoteClient rc) {
		Player p = ((GameClient) rc).getPlayer();
		AttackInfo attack = parseDamage(packet, AttackType.MELEE, p);
		PlayerSkillEffectsData e = attack.getAttackEffect(p);
		int attackCount = 1;
		if (e != null) {
			attackCount = e.getAttackCount();
			if (e.getCooltime() > 0) {
				rc.getSession().send(CommonPackets.writeCooldown(attack.skill, e.getCooltime()));
				p.addCooldown(attack.skill, e.getCooltime());
			}
		}
		p.getMap().sendToAll(writeMeleeAttack(p.getId(), attack), p.getPosition(), p);
		applyAttack(attack, p, attackCount);
	}

	//bow/arrows, claw/stars, guns/bullets (projectiles)
	public static void handleRangedAttack(LittleEndianReader packet, RemoteClient rc) {
		Player p = ((GameClient) rc).getPlayer();
		AttackInfo attack = parseDamage(packet, AttackType.RANGED, p);
		PlayerSkillEffectsData e = attack.getAttackEffect(p);
		int attackCount = 1;
		short useQty;
		if (e == null) { //not a skill
			useQty = 1;
		} else { //skill
			//check if it uses more than one piece of ammo
			useQty = e.getBulletConsume();
			if (useQty == 0)
				useQty = e.getBulletCount();
			if (useQty == 0)
				useQty = 1; //skill uses same amount of ammo as regular attack

			//do the usual skill stuff - cooldowns
			attackCount = e.getAttackCount();
			if (e.getCooltime() > 0) {
				rc.getSession().send(CommonPackets.writeCooldown(attack.skill, e.getCooltime()));
				p.addCooldown(attack.skill, e.getCooltime());
			}
		}
		if (p.isEffectActive(PlayerStatusEffect.SHADOW_PARTNER)) {
			//freakily enough, shadow partner doubles ALL ranged attacks
			useQty *= 2;
			attackCount *= 2;
		}
		int itemId = 0;
		boolean soulArrow = (attack.weaponClass == WeaponClass.BOW || attack.weaponClass == WeaponClass.CROSSBOW) && p.isEffectActive(PlayerStatusEffect.SOUL_ARROW);
		if (!soulArrow) {
			if (attack.cashAmmoSlot != 0) {
				InventorySlot slot = p.getInventory(InventoryType.CASH).get(attack.cashAmmoSlot);
				if (slot == null)
					return; //TODO: hacking
				itemId = slot.getDataId();
			}
			//soul arrow has some funky behaviors if you switch off the bow with
			//a claw or gun... ammoSlot will be 0, and the client will think we
			//can still shoot if we run out of stars or bullets. so we really
			//can't check if the client is hacking here, because using guns and
			//claws with no shadow stars can still have ammoSlot == 0
			boolean shadowStars = attack.weaponClass == WeaponClass.CLAW && p.isEffectActive(PlayerStatusEffect.SHADOW_STARS);
			if (attack.ammoSlot != 0 && !shadowStars) {
				InventorySlot slot = p.getInventory(InventoryType.USE).get(attack.ammoSlot);
				if (slot == null || slot.getQuantity() < useQty)
					return; //TODO: hacking
				slot.setQuantity((short) (slot.getQuantity() - useQty));
				if (slot.getQuantity() == 0 && !InventoryTools.isRechargeable(itemId)) {
					p.getInventory(InventoryType.USE).remove(attack.ammoSlot);
					rc.getSession().send(CommonPackets.writeInventoryClearSlot(InventoryType.USE, attack.ammoSlot));
				} else {
					rc.getSession().send(CommonPackets.writeInventorySlotUpdate(InventoryType.USE, attack.ammoSlot, slot));
				}
				switch (attack.skill) {
					case Skills.ARROW_RAIN:
					case Skills.ARROW_ERUPTION:
					case Skills.ENERGY_ORB:
						//these skills show no visible projectile apparently
						itemId = 0;
						break;
				}
			}
		}
		p.getMap().sendToAll(writeRangedAttack(p.getId(), attack, itemId), p.getPosition(), p);
		applyAttack(attack, p, attackCount);
	}

	public static void handleMagicAttack(LittleEndianReader packet, RemoteClient rc) {
		Player p = ((GameClient) rc).getPlayer();
		AttackInfo attack = parseDamage(packet, AttackType.MAGIC, p);
		p.getMap().sendToAll(writeMagicAttack(p.getId(), attack), p.getPosition(), p);
	}

	private static AttackInfo parseDamage(LittleEndianReader packet, AttackType type, Player p) {
		AttackInfo ret = new AttackInfo();

		if (type != AttackType.SUMMON) {
			/*portals = */packet.readByte();
			ret.setNumAttackedAndDamage(packet.readByte() & 0xFF);
			ret.skill = packet.readInt();
			SkillStats skillStats = SkillDataLoader.getInstance().getSkill(ret.skill);
			ret.charge = skillStats != null && skillStats.isChargedSkill() ? packet.readInt() : 0;
			/*display = */packet.readByte();
			ret.stance = packet.readByte();
			ret.setWeaponClass(packet.readByte());
			ret.speed = packet.readByte();
			/*int tickCount = */packet.readInt();
		} else {
			/*summonId = */packet.readInt();
			/*tickCount = */packet.readInt();
			ret.stance = packet.readByte();
			ret.numAttacked = packet.readByte();
			ret.numDamage = 1;
		}
		if (type == AttackType.RANGED) {
			ret.ammoSlot = packet.readShort();
			ret.cashAmmoSlot = packet.readShort();
			/*aoe = */packet.readBool();

			if (p.isEffectActive(PlayerStatusEffect.SHADOW_STARS))
				ret.ammoItemId = packet.readInt();
		}

		byte numDamaged = ret.numDamage;
		for (int i = 0; i < ret.numAttacked; i++) {
			int mobEid = packet.readInt();
			packet.skip(4);
			/*mobPos = */packet.readPos();
			/*damagePos = */packet.readPos();
			if (ret.skill != Skills.MESO_EXPLOSION)
				/*distance = */packet.readShort();
			else
				numDamaged = packet.readByte();

			int[] allDamageNumbers = new int[numDamaged];
			for (int j = 0; j < numDamaged; j++)
				allDamageNumbers[j] = packet.readInt();
			if (type != AttackType.SUMMON)
				packet.skip(4);
			ret.allDamage.put(Integer.valueOf(mobEid), allDamageNumbers);
		}
		/*playerPos = */packet.readPos();
		if (ret.skill == Skills.MESO_EXPLOSION) {
			byte mesoExplodeCount = packet.readByte();
			ret.mesoExplosion = new int[mesoExplodeCount];
			for (int i = 0; i < mesoExplodeCount; i++) {
				int mesoEid = packet.readInt();
				/*monstersKilled = */packet.readByte();
				ret.mesoExplosion[i] = mesoEid;
			}
			packet.readShort();
		}

		return ret;
	}

	private static void doPickPocketDrops(Player p, Mob monster, Entry<Integer, int[]> oned) {
		int delay = 0;
		int maxmeso = SkillDataLoader.getInstance().getSkill(Skills.PICK_POCKET).getLevel(p.getEffectValue(PlayerStatusEffect.PICKPOCKET).getLevelWhenCast()).getX();
		double reqdamage = 20000;
		final Point mobPos = monster.getPosition();
		final int pEntId = p.getId();
		final GameMap tdmap = p.getMap();

		for (int eachd : oned.getValue()) {
			if (SkillDataLoader.getInstance().getSkill(Skills.PICK_POCKET).getLevel(p.getSkillLevel(4211003)).shouldPerform()) {
				double perc = (double) eachd / reqdamage;

				int dropAmt = Math.min(Math.max((int) (perc * maxmeso), 1), maxmeso);
				final Point tdpos = new Point(mobPos.x + Rng.getGenerator().nextInt(100) - 50, mobPos.y);
				final ItemDrop d = new ItemDrop(dropAmt);

				Timer.getInstance().runAfterDelay(new Runnable() {
					public void run() {
						tdmap.drop(d, mobPos, tdpos, ItemDrop.PICKUP_ALLOW_OWNER, pEntId);
					}
				}, delay);

				delay += 100;
			}
		}
	}

	private static void giveMonsterDiseasesFromActiveBuffs(Player player, Mob monster) {
		PlayerStatusEffectValues v = player.getEffectValue(PlayerStatusEffect.BLIND);
		PlayerSkillEffectsData e;
		if (v != null) {
			e = SkillDataLoader.getInstance().getSkill(v.getSource()).getLevel(player.getSkillLevel(v.getLevelWhenCast()));
			if (e.shouldPerform())
				MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, e);
		}
		v = player.getEffectValue(PlayerStatusEffect.HAMSTRING);
		if (v != null) {
			e = SkillDataLoader.getInstance().getSkill(v.getSource()).getLevel(player.getSkillLevel(v.getLevelWhenCast()));
			if (e.shouldPerform())
				MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, e);
		}
		v = player.getEffectValue(PlayerStatusEffect.CHARGE);
		if (v != null) {
			switch (v.getSource()) {
				case Skills.SWORD_ICE_CHARGE:
				case Skills.BW_BLIZZARD_CHARGE:
					e = SkillDataLoader.getInstance().getSkill(v.getSource()).getLevel(player.getSkillLevel(v.getLevelWhenCast()));
					if (monster.getElementalResistance(Element.ICE) <= Element.EFFECTIVENESS_NORMAL)
						MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, e);
					break;
			}
		}
	}

	private static void giveMonsterDiseasesFromPassiveSkills(Player player, Mob monster, WeaponClass weaponClass, int attackCount) {
		PlayerSkillEffectsData e;
		//(when the client says "Usable up to 3 times against each monster", do
		//they mean 3 per player or 3 max for the mob? I'll assume that it's 3
		//max per mob. I guess this is why post-BB venom is not stackable...)
		if (weaponClass == WeaponClass.CLAW && player.getSkillLevel(Skills.VENOMOUS_STAR) > 0) {
			e = SkillDataLoader.getInstance().getSkill(Skills.VENOMOUS_STAR).getLevel(player.getSkillLevel(Skills.VENOMOUS_STAR));
			for (int i = 0; i < attackCount; i++) {
				if (monster.getVenomCount() < 3 && e.shouldPerform()) {
					monster.addToVenomCount();
					MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, e);
				}
			}
		}
		if (weaponClass == WeaponClass.ONE_HANDED_MELEE && player.getSkillLevel(Skills.VENOMOUS_STAB) > 0) {
			e = SkillDataLoader.getInstance().getSkill(Skills.VENOMOUS_STAB).getLevel(player.getSkillLevel(Skills.VENOMOUS_STAB));
			for (int i = 0; i < attackCount; i++) {
				if (monster.getVenomCount() < 3 && e.shouldPerform()) {
					monster.addToVenomCount();
					MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, e);
				}
			}
		}
	}

	//TODO: handle skills
	private static void applyAttack(AttackInfo attack, final Player player, int attackCount) {
		PlayerSkillEffectsData attackEffect = attack.getAttackEffect(player);
		final GameMap map = player.getMap();
		if (attackEffect != null) { //attack skills
			//apply skill costs
			if (attack.skill != Skills.HEAL) {
				//heal is both an attack (against undead) and a cast skill (healing)
				//so just apply skill costs in the cast skill part
				if (player.isAlive())
					SkillTools.useAttackSkill(player, attackEffect.getDataId(), attackEffect.getLevel());
				else
					player.getClient().getSession().send(CommonPackets.writeEnableActions());
			}
			//perform meso explosion
			if (attack.skill == Skills.MESO_EXPLOSION) {
				int delay = 0;
				for (int meso : attack.mesoExplosion) {
					final ItemDrop drop = (ItemDrop) map.getEntityById(EntityType.DROP, meso);
					if (drop != null) {
						Timer.getInstance().runAfterDelay(new Runnable() {
							public void run() {
								synchronized (drop) {
									if (drop.isAlive())
										map.mesoExplosion(drop, player);
								}
							}
						}, delay);
						delay += 100;
					}
				}
			}
		}

		for (Entry<Integer, int[]> oned : attack.allDamage.entrySet()) {
			//TODO: Synchronize on the monster for aggro and hp stuffs
			Mob monster = (Mob) map.getEntityById(EntityType.MONSTER, oned.getKey().intValue());

			if (monster != null) {
				int totDamageToOneMonster = 0;
				for (int eachd : oned.getValue())
					totDamageToOneMonster += eachd;

				player.checkMonsterAggro(monster);

				//specially handled attack skills
				switch (attack.skill) {
					case Skills.DRAIN:
					case Skills.ENERGY_DRAIN:
						int addHp = (int) ((double) totDamageToOneMonster * (double) SkillDataLoader.getInstance().getSkill(attack.skill).getLevel(player.getSkillLevel(attack.skill)).getX() / 100.0);
						addHp = Math.min(monster.getMaxHp(), Math.min(addHp, player.getCurrentMaxHp() / 2));
						player.gainHp(addHp);
						break;
					case Skills.HEAVENS_HAMMER:
						//TODO: min damage still needs to be calculated. Using -20% as mindamage in the meantime seems to work
						//totDamageToOneMonster = (int) (player.calculateMaxBaseDamage(player.getTotalWatk()) * (SkillDataLoader.getInstance().getSkill(Skills.HEAVENS_HAMMER).getLevel(player.getSkillLevel(Skills.HEAVENS_HAMMER)).getDamage() / 100));
						//totDamageToOneMonster = (int) (Math.floor(Rng.getGenerator().nextDouble() * (totDamageToOneMonster * .2) + totDamageToOneMonster * .8));
						break;
					default:
						//see if the attack skill can give the monster a disease
						if (totDamageToOneMonster > 0 && monster.isAlive() && attackEffect != null)
							if (attackEffect.getMonsterEffect() != null && attackEffect.shouldPerform())
								MonsterStatusEffectTools.applyEffectsAndShowVisuals(monster, player, attackEffect);
						break;
				}
				if (player.isEffectActive(PlayerStatusEffect.PICKPOCKET)) {
					switch (attack.skill) { //TODO: this is probably not an exhaustive list...
						case 0:
						case Skills.DOUBLE_STAB:
						case Skills.SAVAGE_BLOW:
						case Skills.ASSAULTER:
						case Skills.BAND_OF_THIEVES:
						case Skills.CHAKRA:
						case Skills.SHADOWER_TAUNT:
						case Skills.BOOMERANG_STEP:
							doPickPocketDrops(player, monster, oned);
							break;
					}
				}
				//see if any active player buffs can give the monster a disease
				giveMonsterDiseasesFromActiveBuffs(player, monster);
				//see if any passive player skills can give the monster a disease
				giveMonsterDiseasesFromPassiveSkills(player, monster, attack.weaponClass, attackCount);

				player.getMap().damageMonster(player, monster, totDamageToOneMonster);
			}
		}
	}

	private static void writeMesoExplosion(LittleEndianWriter lew, int cid, AttackInfo info) {
		lew.writeInt(cid);
		lew.writeByte(info.getNumAttackedAndDamage());
		lew.writeByte((byte) 0xFF);
		lew.writeInt(info.skill);
		lew.writeByte((byte) 0);
		lew.writeByte(info.stance);
		lew.writeByte(info.speed);
		lew.writeByte((byte) 0x0A);
		lew.writeInt(0);

		for (Entry<Integer, int[]> oned : info.allDamage.entrySet()) {
			lew.writeInt(oned.getKey().intValue());
			lew.writeByte((byte) 0xFF);
			lew.writeByte((byte) oned.getValue().length);
			for (int eachd : oned.getValue())
				lew.writeInt(eachd);
		}
	}

	private static void writeAttackData(LittleEndianWriter lew, int cid, AttackInfo info, int projectile) {
		lew.writeInt(cid);
		lew.writeByte(info.getNumAttackedAndDamage());
		if (info.skill > 0) {
			lew.writeByte((byte) 0xFF); // too low and some skills don't work (?)
			lew.writeInt(info.skill);
		} else
			lew.writeByte((byte) 0);

		lew.writeByte((byte) 0);
		lew.writeByte(info.stance);
		lew.writeByte(info.speed);
		lew.writeByte((byte) 0x0A);
		lew.writeInt(projectile);

		for (Entry<Integer, int[]> oned : info.allDamage.entrySet()) {
			lew.writeInt(oned.getKey().intValue());
			lew.writeByte((byte) 0xFF);
			for (int eachd : oned.getValue())
				lew.writeInt(eachd);
		}
	}

	private static byte[] writeMeleeAttack(int cid, AttackInfo info) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter();
		lew.writeShort(ClientSendOps.MELEE_ATTACK);
		if (info.skill == Skills.MESO_EXPLOSION)
			writeMesoExplosion(lew, cid, info);
		else
			writeAttackData(lew, cid, info, 0);
		return lew.getBytes();
	}

	private static byte[] writeRangedAttack(int cid, AttackInfo info, int projectile) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter();
		lew.writeShort(ClientSendOps.RANGED_ATTACK);
		writeAttackData(lew, cid, info, projectile);
		return lew.getBytes();
	}

	private static byte[] writeMagicAttack(int cid, AttackInfo info) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter();
		lew.writeShort(ClientSendOps.MAGIC_ATTACK);
		writeAttackData(lew, cid, info, 0);
		switch (info.skill) {
			case Skills.FP_BIG_BANG:
			case Skills.IL_BIG_BANG:
			case Skills.BISHOP_BIG_BANG:
				lew.writeInt(info.charge);
				break;
			default:
				lew.writeInt(-1);
				break;
		}
		return lew.getBytes();
	}

	private static class AttackInfo {
		public short ammoSlot, cashAmmoSlot;
		public int skill, charge, ammoItemId;
		public byte numAttacked, numDamage;
		public byte stance;
		public Map<Integer, int[]> allDamage;
		public int[] mesoExplosion;
		public byte speed;
		public WeaponClass weaponClass;

		public AttackInfo() {
			this.speed = 4;
			this.allDamage = new HashMap<Integer, int[]>();
		}

		public PlayerSkillEffectsData getAttackEffect(Player p) {
			if (skill == 0)
				return null;
			SkillStats skillStats = SkillDataLoader.getInstance().getSkill(skill);
			byte skillLevel = p.getSkillLevel(skill);
			if (skillLevel == 0)
				return null;
			return skillStats.getLevel(skillLevel);
		}

		public void setNumAttackedAndDamage(int combined) {
			numAttacked = (byte) (combined / 0x10); //4 bits that are most significant
			numDamage = (byte) (combined % 0x10); //4 bits in that are least significant
		}

		public byte getNumAttackedAndDamage() {
			return (byte) (numAttacked * 0x10 | numDamage);
		}

		public void setWeaponClass(byte value) {
			weaponClass = WeaponClass.valueOf(value);
		}
	}
}

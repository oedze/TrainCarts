package com.bergerkiller.bukkit.tc.controller;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.bases.mutable.VectorAbstract;
import com.bergerkiller.bukkit.common.controller.EntityNetworkController;
import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.common.protocol.CommonPacket;
import com.bergerkiller.bukkit.common.protocol.PacketFields;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.tc.TrainCarts;

public class MinecartMemberNetwork extends EntityNetworkController<CommonMinecart<?>> {
	public static final double ROTATION_K = 0.55;
	public static final int ABSOLUTE_UPDATE_INTERVAL = 200;
	public static final double VELOCITY_SOUND_RADIUS = 16;
	public static final double VELOCITY_SOUND_RADIUS_SQUARED = VELOCITY_SOUND_RADIUS * VELOCITY_SOUND_RADIUS;
	private static final Vector ZERO_VELOCITY = new Vector(0.0, 0.0, 0.0);
	private final Set<Player> velocityUpdateReceivers = new HashSet<Player>();

	public MinecartMemberNetwork() {
		final VectorAbstract velLiveBase = this.velLive;
		this.velLive = new VectorAbstract() {
			public double getX() {return convertVelocity(velLiveBase.getX());}
			public double getY() {return convertVelocity(velLiveBase.getY());}
			public double getZ() {return convertVelocity(velLiveBase.getZ());}
			public VectorAbstract setX(double x) {velLiveBase.setX(x); return this;}
			public VectorAbstract setY(double y) {velLiveBase.setY(y); return this;}
			public VectorAbstract setZ(double z) {velLiveBase.setZ(z); return this;}
		};
	}

	private double convertVelocity(double velocity) {
		return isSoundEnabled() ? MathUtil.clamp(velocity, getEntity().getMaxSpeed()) : 0.0;
	}

	private boolean isSoundEnabled() {
		MinecartMember<?> member = (MinecartMember<?>) entity.getController();
		return (member == null || member.isUnloaded()) ? false : member.getGroup().getProperties().isSoundEnabled();
	}

	private void updateVelocity(Player player) {
		final boolean inRange = isSoundEnabled() && getEntity().loc.distanceSquared(player) <= VELOCITY_SOUND_RADIUS_SQUARED;
		if (LogicUtil.addOrRemove(velocityUpdateReceivers, player, inRange)) {
			CommonPacket velocityPacket;
			if (inRange) {
				// Send the current velocity
				velocityPacket = getVelocityPacket(velSynched.getX(), velSynched.getY(), velSynched.getZ());
			} else {
				// Clear velocity
				velocityPacket = getVelocityPacket(0.0, 0.0, 0.0);
			}
			// Send
			PacketUtil.sendPacket(player, velocityPacket);
		}
	}

	@Override
	public void makeHidden(Player player, boolean instant) {
		super.makeHidden(player, instant);
		this.velocityUpdateReceivers.remove(player);
		PacketUtil.sendPacket(player, PacketFields.ENTITY_VELOCITY.newInstance(getEntity().getEntityId(), ZERO_VELOCITY));
	}

	@Override
	public void makeVisible(Player player) {
		super.makeVisible(player);
		this.velocityUpdateReceivers.add(player);
		this.updateVelocity(player);
	}

	@Override
	public void onSync() {
		try {
			if (entity.isDead()) {
				return;
			}
			MinecartMember<?> member = (MinecartMember<?>) entity.getController();
			if (member.isUnloaded()) {
				// Unloaded: Synchronize just this Minecart
				super.onSync();
				return;
			} else if (member.getIndex() != 0) {
				// Ignore
				return;
			}

			// Update the entire group
			int i;
			MinecartGroup group = member.getGroup();
			final int count = group.size();
			MinecartMemberNetwork[] networkControllers = new MinecartMemberNetwork[count];
			for (i = 0; i < count; i++) {
				EntityNetworkController<?> controller = group.get(i).getEntity().getNetworkController();
				if (!(controller instanceof MinecartMemberNetwork)) {
					// This is not good, but we can fix it...but not here
					group.networkInvalid.set();
					return;
				}
				networkControllers[i] = (MinecartMemberNetwork) controller;
			}

			// Synchronize to the clients
			if (this.getTicksSinceLocationSync() > ABSOLUTE_UPDATE_INTERVAL) {
				// Perform absolute updates
				for (MinecartMemberNetwork controller : networkControllers) {
					controller.syncLocationAbsolute();
					controller.syncVelocity();
					controller.syncMetaData();
					controller.getEntity().setPositionChanged(false);
				}
			} else {
				// Perform relative updates
				boolean needsSync = this.isUpdateTick();
				if (!needsSync) {
					for (i = 0; i < count; i++) {
						MinecartMemberNetwork controller = networkControllers[i];
						if (controller.getEntity().isPositionChanged()) {
							needsSync = true;
							break;
						}
					}
				}
				if (needsSync) {
					boolean moved = false;
					boolean rotated = false;

					// Check whether changes are needed
					for (i = 0; i < count; i++) {
						MinecartMemberNetwork controller = networkControllers[i];						
						moved |= controller.isPositionChanged(MIN_RELATIVE_CHANGE);
						rotated |= controller.isRotationChanged(MIN_RELATIVE_CHANGE);
					}

					// Perform actual updates
					for (i = 0; i < count; i++) {
						MinecartMemberNetwork controller = networkControllers[i];

						// Synchronize location
						if (rotated && !group.get(i).isDerailed()) {
							// Update rotation with control system function
							// This ensures that the Client animation doesn't glitch the rotation
							locLive.addYaw((int) (ROTATION_K * (locLive.getYaw() - locSynched.getYaw())));
							locLive.addPitch((int) (ROTATION_K * (locLive.getPitch() - locSynched.getPitch())));
						}
						controller.syncLocation(moved, rotated);
						controller.getEntity().setPositionChanged(false);

						// Synchronize velocity
						if (controller.getEntity().isVelocityChanged() || controller.isVelocityChanged(MIN_RELATIVE_VELOCITY)) {
							// Reset dirty velocity
							controller.getEntity().setVelocityChanged(false);

							// Send packets to recipients
							velSynched.set(velLive);
							CommonPacket velocityPacket = getVelocityPacket(velSynched.getX(), velSynched.getY(), velSynched.getZ());
							for (Player player : controller.velocityUpdateReceivers) {
								PacketUtil.sendPacket(player, velocityPacket);
							}
						}

						// Update the velocity update receivers
						if (isSoundEnabled()) {
							for (Player player : controller.getViewers()) {
								controller.updateVelocity(player);
							}
						}

						// Synchronize meta data
						controller.syncMetaData();
					}
				}
			}
		} catch (Throwable t) {
			TrainCarts.plugin.log(Level.SEVERE, "Failed to synchronize a network controller:");
			TrainCarts.plugin.handle(t);
		}
	}
}

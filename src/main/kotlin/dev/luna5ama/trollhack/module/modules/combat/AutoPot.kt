package dev.luna5ama.trollhack.module.modules.combat

import dev.luna5ama.trollhack.event.SafeClientEvent
import dev.luna5ama.trollhack.event.events.PacketEvent
import dev.luna5ama.trollhack.event.events.WorldEvent
import dev.luna5ama.trollhack.event.events.player.OnUpdateWalkingPlayerEvent
import dev.luna5ama.trollhack.event.listener
import dev.luna5ama.trollhack.event.safeListener
import dev.luna5ama.trollhack.manager.managers.EntityManager
import dev.luna5ama.trollhack.manager.managers.HotbarSwitchManager
import dev.luna5ama.trollhack.manager.managers.HotbarSwitchManager.ghostSwitch
import dev.luna5ama.trollhack.manager.managers.PlayerPacketManager
import dev.luna5ama.trollhack.manager.managers.PlayerPacketManager.sendPlayerPacket
import dev.luna5ama.trollhack.module.Category
import dev.luna5ama.trollhack.module.Module
import dev.luna5ama.trollhack.util.EntityUtils.isFakeOrSelf
import dev.luna5ama.trollhack.util.EntityUtils.isFriend
import dev.luna5ama.trollhack.util.TickTimer
import dev.luna5ama.trollhack.util.accessor.entityID
import dev.luna5ama.trollhack.util.interfaces.DisplayEnum
import dev.luna5ama.trollhack.util.inventory.InventoryTask
import dev.luna5ama.trollhack.util.inventory.confirmedOrTrue
import dev.luna5ama.trollhack.util.inventory.inventoryTask
import dev.luna5ama.trollhack.util.inventory.operation.swapWith
import dev.luna5ama.trollhack.util.inventory.slot.*
import dev.luna5ama.trollhack.util.math.vector.Vec2f
import dev.luna5ama.trollhack.util.world.getGroundLevel
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import net.minecraft.entity.Entity
import net.minecraft.entity.projectile.EntityPotion
import net.minecraft.init.Items
import net.minecraft.init.MobEffects
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketPlayerTryUseItem
import net.minecraft.network.play.server.SPacketDestroyEntities
import net.minecraft.network.play.server.SPacketEntityStatus
import net.minecraft.potion.Potion
import net.minecraft.potion.PotionUtils
import net.minecraft.util.EnumHand
import kotlin.math.abs
import kotlin.math.sqrt

internal object AutoPot : Module(
    name = "Auto Pot",
    description = "药水哥",
    category = Category.COMBAT,
    modulePriority = 100
) {
    private val switchMode by setting("Switch Mode", HotbarSwitchManager.Override.PICK)
    private val slot by setting("Slot", 7, 1..9, 1)
    private val heal by setting("Heal", true)
    private val healHealth by setting("Heal Health", 12.0f, 0.0f..20.0f, 0.5f, ::heal)
    private val healDelay by setting("Heal Delay", 500, 0..10000, 50, ::heal)
    private val weakness by setting("Weakness", false)
    private val weaknessFriendRange by setting("Weakness Friend Range", 5.0f, 0.0f..10.0f, 0.5f, ::weakness)
    private val weaknessRange by setting("Weakness Range", 1.8f, 0.0f..4.0f, 0.1f, ::weakness)
    private val weaknessDelay by setting("Weakness Delay", 1500, 0..10000, 50, ::weakness)
    private val speed by setting("Speed", true)
    private val speedDelay by setting("Speed Delay", 5000, 0..10000, 50, ::speed)

    private val weaknessTimeMap = Int2LongOpenHashMap().apply { defaultReturnValue(0x22) }

    private var hotbarSlot: HotbarSlot? = null
    private var lastTask: InventoryTask? = null
    private var potionType = PotionType.NONE

    override fun getHudInfo(): String {
        return potionType.displayString
    }

    init {
        onDisable {
            hotbarSlot = null
            lastTask = null
            potionType = PotionType.NONE
        }

        listener<PacketEvent.PostReceive> {
            when (it.packet) {
                is SPacketDestroyEntities -> {
                    it.packet.entityIDs.forEach(weaknessTimeMap::remove)
                }
                is SPacketEntityStatus -> {
                    if (it.packet.opCode.toInt() == 35) {
                        weaknessTimeMap.remove(it.packet.entityID)
                    }
                }
            }
        }

        safeListener<WorldEvent.Entity.Remove> { event ->
            if (event.entity is EntityPotion) {
                val effect = PotionUtils.getEffectsFromStack(event.entity.potion)
                    .firstOrNull { it.potion == MobEffects.WEAKNESS } ?: return@safeListener

                val box = event.entity.entityBoundingBox.grow(4.0, 2.0, 4.0)

                EntityManager.players.asSequence()
                    .filter { it.isEntityAlive }
                    .filterNot { it.isFakeOrSelf }
                    .filterNot { it.isFriend }
                    .filter { box.intersects(it.entityBoundingBox) }
                    .forEach {
                        val distSq = event.entity.getDistanceSq(it)

                        if (distSq < 16.0) {
                            val factor = sqrt(distSq) * 0.75
                            val duration = (factor * effect.duration + 0.5)
                            weaknessTimeMap[it.entityId] = System.currentTimeMillis() + duration.toLong() * 50L
                        }
                    }
            }
        }

        safeListener<OnUpdateWalkingPlayerEvent.Pre> {
            if (!groundCheck()) {
                potionType = PotionType.NONE
                hotbarSlot = null
                return@safeListener
            }

            if (lastTask.confirmedOrTrue && potionType == PotionType.NONE) {
                potionType = PotionType.values().first {
                    it.check(this)
                }
            }

            val potionType = potionType

            hotbarSlot = if (potionType != PotionType.NONE) {
                getSlot(potionType)?.also {
                    sendPlayerPacket {
                        rotate(Vec2f(player.rotationYaw, 90.0f))
                    }
                }
            } else {
                null
            }
        }

        safeListener<OnUpdateWalkingPlayerEvent.Post> {
            hotbarSlot?.let {
                if (PlayerPacketManager.prevRotation.y > 85.0f && PlayerPacketManager.rotation.y > 85.0f) {
                    ghostSwitch(switchMode, it) {
                        connection.sendPacket(CPacketPlayerTryUseItem(EnumHand.MAIN_HAND))
                        potionType.timer.reset()
                        potionType = PotionType.NONE
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.groundCheck(): Boolean {
        return player.onGround
            || player.posY - world.getGroundLevel(player) < 3.0
    }

    private fun SafeClientEvent.getSlot(potionType: PotionType): HotbarSlot? {
        return player.hotbarSlots.findPotion(potionType)
            ?: run {
                (player.storageSlots + player.craftingSlots).findPotion(potionType)?.let {
                    inventoryTask {
                        swapWith(it, player.hotbarSlots[slot - 1])
                        delay(0L)
                        postDelay(0L)
                    }
                }
                null
            }
    }

    private fun <T : Slot> List<T>.findPotion(potionType: PotionType): T? {
        return this.asSequence()
            .filter {
                val stack = it.stack
                stack.item == Items.SPLASH_POTION && stack.hasPotion(potionType.potion)
            }.minByOrNull {
                it.stack.count
            }
    }

    private enum class PotionType(override val displayName: CharSequence, val potion: Potion) : DisplayEnum {
        INSTANT_HEALTH("Heal", MobEffects.INSTANT_HEALTH) {
            override fun check(event: SafeClientEvent): Boolean {
                return heal
                    && timer.tick(healDelay)
                    && event.player.health <= healHealth
                    && super.check(event)
            }
        },

        WEAKNESS("Weakness", MobEffects.WEAKNESS) {
            override fun check(event: SafeClientEvent): Boolean {
                return weakness
                    && timer.tick(weaknessDelay)
                    && super.check(event)
                    && (weaknessFriendRange == 0.0f || EntityManager.players.asSequence()
                    .filterNot { it.isFakeOrSelf }
                    .filter { it.isFriend }
                    .all { event.player.getDistanceSq(it) > weaknessFriendRange * weaknessFriendRange })
                    && EntityManager.players.asSequence()
                    .filterNot { it.isFriend }
                    .filterNot { it.isFakeOrSelf }
                    .filter { event.isInRange(it) }
                    .any { !weaknessTimeMap.containsKey(it.entityId) }
            }

            private fun SafeClientEvent.isInRange(entity: Entity): Boolean {
                return abs(player.posX - entity.posX) <= 4.125
                    && abs(player.posY - entity.posY) <= 2.125
                    && abs(player.posZ - entity.posZ) <= 4.125
                    && player.getDistanceSq(entity) <= weaknessRange * weaknessRange
            }
        },

        SPEED("Speed", MobEffects.SPEED) {
            override fun check(event: SafeClientEvent): Boolean {
                return speed
                    && timer.tick(speedDelay)
                    && !event.player.isPotionActive(MobEffects.SPEED)
                    && super.check(event)
            }
        },

        NONE("", MobEffects.LUCK) {
            override fun check(event: SafeClientEvent): Boolean {
                return true
            }
        };

        val timer = TickTimer()

        open fun check(event: SafeClientEvent): Boolean {
            return (event.player.inventorySlots + event.player.craftingSlots).hasItem(Items.SPLASH_POTION) { itemStack ->
                itemStack.hasPotion(potion)
            }
        }
    }

    private fun ItemStack.hasPotion(potion: Potion): Boolean {
        return PotionUtils.getEffectsFromStack(this).any { it.potion == potion }
    }
}
package xyz.kaleidiodev.kaleidiosguns.item;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShootableItem;
import net.minecraft.item.UseAction;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.stats.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import xyz.kaleidiodev.kaleidiosguns.entity.BulletEntity;
import xyz.kaleidiodev.kaleidiosguns.registry.ModEnchantments;
import xyz.kaleidiodev.kaleidiosguns.registry.ModItems;
import xyz.kaleidiodev.kaleidiosguns.registry.ModSounds;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static xyz.kaleidiodev.kaleidiosguns.KaleidiosGuns.VivecraftForgeExtensionPresent;

public class GunItem extends ShootableItem {

	protected int bonusDamage;
	protected double damageMultiplier;
	protected int fireDelay;
	protected double inaccuracy;
	protected double projectileSpeed = 3;
	private final int enchantability;
	protected boolean ignoreInvulnerability = true;
	protected double chanceFreeShot = 0;
	protected SoundEvent fireSound = ModSounds.gun;
	//Hey guess what if I just put the repair material it crashes... so well let's do like vanilla and just use a supplier
	protected Supplier<Ingredient> repairMaterial;

	public GunItem(Properties properties, int bonusDamage, double damageMultiplier, int fireDelay, double inaccuracy, int enchantability) {
		super(properties);
		this.bonusDamage = bonusDamage;
		this.damageMultiplier = damageMultiplier;
		this.enchantability = enchantability;
		this.fireDelay = fireDelay;
		this.inaccuracy = inaccuracy;
	}

	@Override
	public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack gun = player.getItemInHand(hand);
		//"Oh yeah I will use the vanilla method so that quivers can do their thing"
		//guess what the quivers suck
		ItemStack ammo = player.getProjectile(gun);

		if (!ammo.isEmpty() || player.abilities.instabuild) {
			if (ammo.isEmpty()) ammo = new ItemStack(ModItems.flintBullet);

			IBullet bulletItem = (IBullet) (ammo.getItem() instanceof IBullet ? ammo.getItem() : ModItems.flintBullet);
			if (!world.isClientSide) {
				player.startUsingItem(hand);
				boolean bulletFree = player.abilities.instabuild || !shouldConsumeAmmo(gun, player);

				//Workaround for quivers not respecting getAmmoPredicate()
				ItemStack shotAmmo = ammo.getItem() instanceof IBullet ? ammo : new ItemStack(ModItems.flintBullet);
				fireWeapon(world, player, gun, shotAmmo, bulletItem, bulletFree);

				gun.hurtAndBreak(1, player, (p) -> p.broadcastBreakEvent(player.getUsedItemHand()));
				if (!bulletFree) bulletItem.consume(ammo, player);
			}

			world.playSound(null, player.getX(), player.getY(), player.getZ(), fireSound, SoundCategory.PLAYERS, 1.0F, 1.0F);
			player.awardStat(Stats.ITEM_USED.get(this));

			player.getCooldowns().addCooldown(this, getFireDelay(gun, player));
			return ActionResult.consume(gun);
		}
		else return ActionResult.fail(gun);
	}

	/**
	 * Fires one shot after all the checks have passed. You can actually fire whatever you want here.<br>
	 * Ammo is consumed afterwards, we're only shooting the bullet(s) here.
	 * @param world world
	 * @param player Player that shoots
	 * @param gun Gun shooting
	 * @param ammo Ammo being shot
	 * @param bulletItem IBullet used for the shot, may not match the ammo
	 * @param bulletFree true if no ammo was actually consumed (creative or Preserving enchant for example)
	 */
	protected void fireWeapon(World world, PlayerEntity player, ItemStack gun, ItemStack ammo, IBullet bulletItem, boolean bulletFree) {
		BulletEntity shot = bulletItem.createProjectile(world, ammo, player, gun.getItem() == ModItems.streamGatling);
		shot.shootFromRotation(player, player.xRot, player.yRot, 0, (float)getProjectileSpeed(gun, player), VivecraftForgeExtensionPresent ? 0.0F : (float)getInaccuracy(gun, player));

		//subtract player velocity to make the bullet independent
		Vector3d projectileMotion = player.getDeltaMovement();
		shot.setDeltaMovement(shot.getDeltaMovement().subtract(projectileMotion.x, player.isOnGround() ? 0.0D : projectileMotion.y, projectileMotion.z));

		shot.setInaccuracy(getInaccuracy(gun, player));

		shot.setDamage((shot.getDamage() + getBonusDamage(gun, player)) * getDamageMultiplier(gun, player));
		shot.setIgnoreInvulnerability(ignoreInvulnerability);
		changeBullet(world, player, gun, shot, bulletFree);

		world.addFreshEntity(shot);
	}

	/**
	 * Lets the gun do custom stuff to default bullets without redoing all the base stuff from shoot.
	 */
	protected void changeBullet(World world, PlayerEntity player, ItemStack gun, BulletEntity bullet, boolean bulletFree) {

	}

	/**
	 * Rolls chance to know if ammo should be consumed for the shot. Applies both the baseline chance and Preserving enchantment.<br>
	 * If you change this don't forget to tweak getInverseChanceFreeShot accordingly for the tooltip (and call super).
	 */
	public boolean shouldConsumeAmmo(ItemStack stack, PlayerEntity player) {
		if (chanceFreeShot > 0 && random.nextDouble() < chanceFreeShot) return false;

		int preserving = EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.preserving, stack);
		//(level) in (level + 2) chance to not consume
		if (preserving >= 1 && random.nextInt(preserving + 2) >= 2) return false;

		return true;
	}

	/**
	 * Gets the flat bonus damage (applied BEFORE the multiplier). This takes into account Impact enchantment.
	 */
	public double getBonusDamage(ItemStack stack, @Nullable PlayerEntity player) {
		int impact = EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.impact, stack);
		return bonusDamage + (impact >= 1 ? (impact + 1) : 0);
	}

	public double getDamageMultiplier(ItemStack stack, @Nullable PlayerEntity player) {
		return damageMultiplier;
	}

	/**
	 * Gets the min time in ticks between 2 shots. This takes into account Sleight of Hand enchantment.
	 */
	public int getFireDelay(ItemStack stack, @Nullable PlayerEntity player) {
		return Math.max(1, fireDelay - (int)(fireDelay * EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.sleightOfHand, stack) * 0.25));
	}

	/**
	 * Checks if the gun has baseline perfect accuracy.<br>
	 * Used for tooltip and for Bullseye (which can't be applied since it would do nothing).
	 */
	public boolean hasPerfectAccuracy() {
		return inaccuracy <= 0;
	}

	/**
	 * Gets the inaccuracy, taking into account Bullseye enchantment.<br>
	 * Accuracy is actually inarccuracy internally, because it's easier to math.<br>
	 * The formula is just accuracy = 1 / inaccuracy.
	 */
	public double getInaccuracy(ItemStack stack, @Nullable PlayerEntity player) {
		return Math.max(0, inaccuracy / (EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.bullseye, stack) + 1.0));
	}

	public double getProjectileSpeed(ItemStack stack, @Nullable PlayerEntity player) {
		return projectileSpeed;
	}

	/**
	 * Chance to actually CONSUME ammo, to properly multiply probabilities together.<br>
	 * Tooltip then does the math to display it nicely.
	 */
	public double getInverseChanceFreeShot(ItemStack stack, @Nullable PlayerEntity player) {
		double chance = 1 - chanceFreeShot;
		int preserving = EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.preserving, stack);
		if (preserving >= 1) chance *= 2.0/(preserving + 2);
		return chance;
	}

	/**
	 * Says if the damage is changed from base value. Used for tooltip.
	 */
	protected boolean isDamageModified(ItemStack stack) {
		return EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.impact, stack) >= 1;
	}

	/**
	 * Says if the fire delay is changed from base value. Used for tooltip.
	 */
	protected boolean isFireDelayModified(ItemStack stack) {
		return EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.sleightOfHand, stack) >= 1;
	}

	/**
	 * Says if the (in)accuracy is changed from base value. Used for tooltip.
	 */
	protected boolean isInaccuracyModified(ItemStack stack) {
		return !hasPerfectAccuracy() && EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.bullseye, stack) >= 1;
	}

	/**
	 * Says if the chance for free shots is changed from base value. Used for tooltip.
	 */
	protected boolean isChanceFreeShotModified(ItemStack stack) {
		return EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.preserving, stack) >= 1;
	}

	/**
	 * Sets whether the bullets ignore invulnerability frame (default no), used when making the item for registering.
	 */
	public GunItem ignoreInvulnerability(boolean ignoreInvulnerability) {
		this.ignoreInvulnerability = ignoreInvulnerability;
		return this;
	}

	/**
	 * Sets a chance to NOT consume ammo, used when making the item for registering.
	 */
	public GunItem chanceFreeShot(double chanceFreeShot) {
		this.chanceFreeShot = chanceFreeShot;
		return this;
	}

	/**
	 * Sets the firing sound, used when making the item for registering.
	 */
	public GunItem fireSound(SoundEvent fireSound) {
		this.fireSound = fireSound;
		return this;
	}

	/**
	 * Sets a projectile speed, used when making the item for registering.<br>
	 * Base value is 3. High values (like 5-6) cause weird behavior so don't with the base bullets.
	 */
	public GunItem projectileSpeed(double projectileSpeed) {
		this.projectileSpeed = projectileSpeed;
		return this;
	}

	/**
	 * Sets the repair material, used when making the item for registering.
	 */
	public GunItem repair(Supplier<Ingredient> repairMaterial) {
		this.repairMaterial = repairMaterial;
		return this;
	}

	@Override
	public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
		//Disallow Bullseye if the gun has perfect accuracy
		if (enchantment == ModEnchantments.bullseye && hasPerfectAccuracy()) return false;
		return super.canApplyAtEnchantingTable(stack, enchantment);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flagIn) {
		if (Screen.hasShiftDown()) {
			//Damage
			double damageMultiplier = getDamageMultiplier(stack, null);
			double damageBonus = getBonusDamage(stack, null) * damageMultiplier;

			if (damageMultiplier != 1) {
				if (damageBonus != 0) tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.damage.both" + (isDamageModified(stack) ? ".modified" : ""), ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(damageMultiplier), ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(damageBonus)));
				else tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.damage.mult" + (isDamageModified(stack) ? ".modified" : ""), ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(damageMultiplier)));
			}
			else if (damageBonus != 0) tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.damage.flat" + (isDamageModified(stack) ? ".modified" : ""), ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(damageBonus)));

			//Fire rate
			int fireDelay = getFireDelay(stack, null);
			tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.firerate" + (isFireDelayModified(stack) ? ".modified" : ""), fireDelay, (60*20) / fireDelay));

			//Accuracy
			double inaccuracy = getInaccuracy(stack, null);
			if (inaccuracy <= 0) tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.accuracy.perfect" + (isInaccuracyModified(stack) ? ".modified" : "")));
			else tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.accuracy" + (isInaccuracyModified(stack) ? ".modified" : ""), ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(1.0 / inaccuracy)));

			//Chance to not consume ammo
			double inverseChanceFree = getInverseChanceFreeShot(stack, null);
			if (inverseChanceFree < 1) tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.chance_free" + (isChanceFreeShotModified(stack) ? ".modified" : ""), (int)((1 - inverseChanceFree) * 100)));

			//Other stats
			if (ignoreInvulnerability) tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.gun.ignore_invulnerability").withStyle(TextFormatting.GRAY));

			addExtraStatsTooltip(stack, world, tooltip);
		}
		else tooltip.add(new TranslationTextComponent("tooltip.kaleidiosguns.shift"));
	}

	/**
	 * Add more tooltips that will be displayed below the base stats.
	 */
	protected void addExtraStatsTooltip(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip) {

	}

	@Override
	public UseAction getUseAnimation(ItemStack stack) {
		return UseAction.BOW;
	}

	@Override
	public int getEnchantmentValue() {
		return enchantability;
	}

	//TODO ammo types
	private static final Predicate<ItemStack> BULLETS = (stack) -> stack.getItem() instanceof IBullet && ((IBullet)stack.getItem()).hasAmmo(stack);

	@Override
	public Predicate<ItemStack> getAllSupportedProjectiles() {
		return BULLETS;
	}

	@Override
	public int getDefaultProjectileRange() {
		return 15;
	}

	@Override
	public boolean isValidRepairItem(ItemStack toRepair, ItemStack repair) {
		return (repairMaterial != null && repairMaterial.get().test(repair)) || super.isValidRepairItem(toRepair, repair);
	}

	@Override
	public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
		return !ItemStack.isSameIgnoreDurability(oldStack, newStack);
	}

	private void shootProjectile(ProjectileEntity entity, float pX, float pY, float pZ, float pVelocity, float pInaccuracy) {
		float f = -MathHelper.sin(pY * ((float)Math.PI / 180F)) * MathHelper.cos(pX * ((float)Math.PI / 180F));
		float f1 = -MathHelper.sin((pX + pZ) * ((float)Math.PI / 180F));
		float f2 = MathHelper.cos(pY * ((float)Math.PI / 180F)) * MathHelper.cos(pX * ((float)Math.PI / 180F));
		entity.shoot(f, f1, f2, pVelocity, pInaccuracy);
	}

	@Override
	public int getUseDuration(ItemStack pStack) {
		return Math.max(getFireDelay(pStack, null) / 20, 10);
	}

	//Event to change add bullet inaccuracy if VivecraftForgeExtension is present
	@SubscribeEvent(priority = EventPriority.LOW)
	public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
		if (event.getEntity() instanceof BulletEntity) {
			BulletEntity shot = (BulletEntity)event.getEntity();
			Vector3d direction = shot.getDeltaMovement();
			double velocity = direction.length();
			direction = direction.normalize().add(random.nextGaussian() * 0.0075 * shot.getInaccuracy(), random.nextGaussian() * 0.0075 * shot.getInaccuracy(), random.nextGaussian() * 0.0075 * shot.getInaccuracy()).scale(velocity);
			shot.setDeltaMovement(direction);
			float horizontalDistance = MathHelper.sqrt(direction.x * direction.x + direction.z * direction.z);
			shot.yRot = (float)(MathHelper.atan2(direction.x, direction.z) * (double)(180F / (float)Math.PI));
			shot.xRot = (float)(MathHelper.atan2(direction.y, horizontalDistance) * (double)(180F / (float)Math.PI));
			shot.yRotO = shot.yRot;
			shot.xRotO = shot.xRot;
		}
	}
}

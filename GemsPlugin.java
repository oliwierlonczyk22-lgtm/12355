package dev.gems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class GemsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final byte TAG_FIREBALL = 1;     // blokami steruje config
    private static final byte TAG_NO_BLOCKS = 2;    // nigdy nie niszczy bloków

    private NamespacedKey gemKey;
    private NamespacedKey equippedKey;
    private NamespacedKey projKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    // config
    private double dropChance;
    private boolean announceDrops;
    private boolean breakBlocks;

    // kryształy, które już raz wypadły (każdy może wypaść tylko raz)
    private final Set<Ability> dropped = EnumSet.noneOf(Ability.class);
    private File dataFile;

    // anty-latanie
    private final Set<UUID> flightUsed = new HashSet<>();
    private final Map<UUID, Long> flightLockUntil = new HashMap<>();

    // tymczasowe pajęczyny (żeby posprzątać przy wyłączeniu)
    private final Set<Block> webs = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        gemKey = new NamespacedKey(this, "gem");
        equippedKey = new NamespacedKey(this, "equipped");
        projKey = new NamespacedKey(this, "gemproj");
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("gem");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) updateFlight(p);

        // Po użyciu umiejętności lot jest zablokowany, dopóki gracz nie wyląduje.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (flightUsed.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!flightUsed.contains(id)) continue;
                Long until = flightLockUntil.get(id);
                if (until != null && now < until) continue;
                if (isGrounded(p)) {
                    flightUsed.remove(id);
                    flightLockUntil.remove(id);
                    updateFlight(p);
                }
            }
        }, 2L, 2L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode gm = p.getGameMode();
            if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
        }
        for (Block b : webs) {
            if (b.getType() == Material.COBWEB) b.setType(Material.AIR);
        }
        webs.clear();
    }

    private void loadSettings() {
        dropChance = getConfig().getDouble("drop-chance", 0.05);
        announceDrops = getConfig().getBoolean("announce-drops", false);
        breakBlocks = getConfig().getBoolean("explosions-break-blocks", false);
    }

    // ------------------------------------------------------------ dane (unikalne dropy)

    private void loadData() {
        dropped.clear();
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("dropped")) {
            Ability a = parse(s);
            if (a != null) dropped.add(a);
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> names = new ArrayList<>();
        for (Ability a : dropped) names.add(a.name());
        y.set("dropped", names);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nie udało się zapisać data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ items

    public ItemStack createGem(Ability a) {
        ItemStack it = new ItemStack(a.material);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Kryształ: " + a.displayName, a.color)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text(a.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("PPM - załóż kryształ", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Podwójna spacja - użyj", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Cooldown: " + a.cooldown + "s", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(gemKey, PersistentDataType.STRING, a.name());
        it.setItemMeta(m);
        return it;
    }

    private Ability abilityOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(gemKey, PersistentDataType.STRING);
        return parse(s);
    }

    private Ability parse(String s) {
        if (s == null) return null;
        try {
            return Ability.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Ability getEquipped(Player p) {
        return parse(p.getPersistentDataContainer().get(equippedKey, PersistentDataType.STRING));
    }

    private void setEquipped(Player p, Ability a) {
        if (a == null) p.getPersistentDataContainer().remove(equippedKey);
        else p.getPersistentDataContainer().set(equippedKey, PersistentDataType.STRING, a.name());
        updateFlight(p);
    }

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    // ------------------------------------------------------------ latanie (podwójna spacja)

    @SuppressWarnings("deprecation")
    private boolean isGrounded(Player p) {
        return p.isOnGround() || p.isInWater();
    }

    /**
     * allowFlight musi być włączone, żeby podwójna spacja w ogóle wywołała event.
     * Po użyciu wyłączamy je aż do wylądowania, więc spamowanie spacji nie daje latania.
     */
    private void updateFlight(Player p) {
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;
        boolean allow = getEquipped(p) != null && !flightUsed.contains(p.getUniqueId());
        if (p.getAllowFlight() != allow) p.setAllowFlight(allow);
        if (!allow && p.isFlying()) p.setFlying(false);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        if (mob instanceof Player || mob instanceof ArmorStand) return;
        Player killer = mob.getKiller();
        if (killer == null) return;

        // tylko kryształy, które jeszcze nigdy nie wypadły
        List<Ability> pool = new ArrayList<>();
        for (Ability a : Ability.values()) if (!dropped.contains(a)) pool.add(a);
        if (pool.isEmpty()) return;
        if (random.nextDouble() >= dropChance) return;

        Ability a = pool.get(random.nextInt(pool.size()));
        dropped.add(a);
        saveData();
        e.getDrops().add(createGem(a));

        if (announceDrops) {
            Bukkit.broadcast(Component.text("Kryształ ", NamedTextColor.GRAY)
                    .append(Component.text(a.displayName, a.color))
                    .append(Component.text(" został znaleziony przez " + killer.getName() + "!", NamedTextColor.GRAY)));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Ability a = abilityOf(hand);
        if (a == null) return;
        e.setCancelled(true);

        Ability old = getEquipped(p);
        if (old == a) {
            p.sendActionBar(Component.text("Ten kryształ jest już założony.", NamedTextColor.RED));
            return;
        }
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);

        if (old != null) giveItem(p, createGem(old)); // limit: 1 założony kryształ
        setEquipped(p, a);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f, 1.2f);
        p.sendActionBar(Component.text("Założono: " + a.displayName + " - użyj podwójną spacją", a.color));
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (!e.isFlying()) return;
        Ability a = getEquipped(p);
        if (a == null) return;

        e.setCancelled(true);
        p.setFlying(false);

        // Blokada lotu do czasu wylądowania - to naprawia latanie po spamowaniu spacji.
        UUID id = p.getUniqueId();
        flightUsed.add(id);
        flightLockUntil.put(id, System.currentTimeMillis() + 400L);
        p.setAllowFlight(false);

        tryUse(p, a);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        flightUsed.remove(e.getPlayer().getUniqueId());
        updateFlight(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        flightUsed.remove(p.getUniqueId());
        flightLockUntil.remove(p.getUniqueId());
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
            p.setAllowFlight(false);
            p.setFlying(false);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        flightUsed.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(this, () -> updateFlight(p));
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> updateFlight(p));
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        Byte tag = e.getEntity().getPersistentDataContainer().get(projKey, PersistentDataType.BYTE);
        if (tag == null) return;
        if (tag == TAG_NO_BLOCKS || !breakBlocks) e.blockList().clear();
    }

    // ------------------------------------------------------------ menu

    private static class MenuHolder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Kryształy"));
        holder.inv = inv;
        int slot = 0;
        for (Ability a : Ability.values()) inv.setItem(slot++, createGem(a));
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Ability a = abilityOf(e.getCurrentItem());
        if (a == null) return;
        if (e.getWhoClicked() instanceof Player p) {
            giveItem(p, createGem(a));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ abilities

    private void tryUse(Player p, Ability a) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && until > now) {
            double left = (until - now) / 1000.0;
            p.sendActionBar(Component.text(String.format(Locale.US, "%s: cooldown %.1fs", a.displayName, left),
                    NamedTextColor.RED));
            return;
        }
        // cooldown liczymy dopiero, gdy umiejętność faktycznie się wykonała
        if (!useAbility(p, a)) {
            p.sendActionBar(Component.text(a.displayName + ": brak celu / nie można użyć", NamedTextColor.RED));
            return;
        }
        cooldowns.put(p.getUniqueId(), now + a.cooldown * 1000L);
        p.sendActionBar(Component.text("Użyto: " + a.displayName, a.color));
    }

    private boolean validTarget(Entity en, Player p) {
        if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || en.equals(p) || le.isDead()) return false;
        return !(en instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR);
    }

    private List<LivingEntity> enemies(Player p, double r) {
        List<LivingEntity> list = new ArrayList<>();
        Location pl = p.getLocation();
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (!validTarget(en, p)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distanceSquared(pl) > r * r) continue;
            list.add(le);
        }
        return list;
    }

    private LivingEntity targetEntity(Player p, double range) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        RayTraceResult r = w.rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
                en -> validTarget(en, p));
        return r != null && r.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    private void line(Location a, Location b, Particle particle) {
        World w = a.getWorld();
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize();
        for (double i = 0; i < len; i += 0.5) {
            w.spawnParticle(particle, a.clone().add(d.clone().multiply(i)), 1, 0, 0, 0, 0);
        }
    }

    private void ring(Location c, double radius, Particle particle, int points) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double ang = 2 * Math.PI * i / points;
            w.spawnParticle(particle, c.clone().add(Math.cos(ang) * radius, 0.2, Math.sin(ang) * radius),
                    1, 0, 0, 0, 0);
        }
    }

    /** Wybuch o mocy TNT (4.0) zadaje obrażenia z winy gracza. */
    private void explode(Player p, Location loc, float power) {
        loc.getWorld().createExplosion(p, loc, power, false, breakBlocks);
    }

    private void meteor(Player p, Location target) {
        World w = target.getWorld();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double h = Math.max(0, 25 - t * 1.25);
                Location pt = target.clone().add(0, h, 0);
                w.spawnParticle(Particle.FLAME, pt, 25, 0.4, 0.4, 0.4, 0.02);
                w.spawnParticle(Particle.LARGE_SMOKE, pt, 8, 0.3, 0.3, 0.3, 0.01);
                ring(target, 2.5, Particle.FLAME, 24);
                if (t % 6 == 0) w.playSound(target, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.5f);
                if (t >= 20) {
                    cancel();
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, target, 1);
                    explode(p, target.clone().add(0, 0.5, 0), 5f);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private boolean useAbility(Player p, Ability a) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        switch (a) {
            case DASH -> {
                Vector v = dir.clone().setY(0);
                if (v.lengthSquared() < 0.001) v = dir.clone();
                v = v.normalize().multiply(1.9).setY(0.2);
                p.setVelocity(v);
                w.spawnParticle(Particle.CLOUD, loc, 20, 0.3, 0.1, 0.3, 0.05);
                w.playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 1.2f);
            }
            case DOUBLE_JUMP -> {
                Vector cur = p.getVelocity();
                p.setVelocity(new Vector(cur.getX() * 1.2, 0.95, cur.getZ() * 1.2));
                p.setFallDistance(0f);
                w.spawnParticle(Particle.CLOUD, loc, 20, 0.3, 0.05, 0.3, 0.05);
                w.playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 1.6f);
            }
            case BLINK -> {
                Vector h = dir.clone().setY(0);
                if (h.lengthSquared() < 0.001) h = new Vector(0, 0, 1);
                h.normalize();
                boolean moved = false;
                for (int d = 8; d >= 1; d--) {
                    Location dest = loc.clone().add(h.clone().multiply(d));
                    if (dest.getBlock().isPassable() && dest.clone().add(0, 1, 0).getBlock().isPassable()) {
                        w.spawnParticle(Particle.PORTAL, loc.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
                        p.teleport(dest);
                        w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
                        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                        moved = true;
                        break;
                    }
                }
                if (!moved) return false;
            }
            case PEARL -> {
                p.launchProjectile(EnderPearl.class);
                w.playSound(loc, Sound.ENTITY_ENDER_PEARL_THROW, 1f, 1f);
            }
            case ROCKET -> {
                Vector cur = p.getVelocity();
                p.setVelocity(new Vector(cur.getX(), 1.7, cur.getZ()));
                p.setFallDistance(0f);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0));
                w.spawnParticle(Particle.FLAME, loc, 40, 0.3, 0.1, 0.3, 0.1);
                w.spawnParticle(Particle.CLOUD, loc, 20, 0.3, 0.1, 0.3, 0.05);
                w.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            }
            case SWAP -> {
                LivingEntity t = targetEntity(p, 30);
                if (t == null) return false;
                Location pl = p.getLocation();
                Location tl = t.getLocation();
                Location newP = tl.clone();
                newP.setYaw(pl.getYaw());
                newP.setPitch(pl.getPitch());
                Location newT = pl.clone();
                newT.setYaw(tl.getYaw());
                newT.setPitch(tl.getPitch());
                w.spawnParticle(Particle.PORTAL, pl.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
                w.spawnParticle(Particle.PORTAL, tl.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
                p.teleport(newP);
                t.teleport(newT);
                w.playSound(newP, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            }
            case SPEED -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 160, 2));
                w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
            }
            case WIND_CHARGE -> {
                p.launchProjectile(WindCharge.class);
            }
            case ARROW_VOLLEY -> {
                for (int i = -3; i <= 3; i++) {
                    Vector v = dir.clone().rotateAroundY(Math.toRadians(i * 6)).multiply(2.6);
                    Arrow ar = p.launchProjectile(Arrow.class, v);
                    ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    ar.setCritical(true);
                    ar.setDamage(2.0);
                    Bukkit.getScheduler().runTaskLater(this, ar::remove, 100L);
                }
                w.playSound(loc, Sound.ENTITY_ARROW_SHOOT, 1f, 0.8f);
            }
            case WITHER_SKULL -> {
                WitherSkull ws = p.launchProjectile(WitherSkull.class, dir.clone().multiply(1.5));
                ws.setIsIncendiary(false);
                ws.setYield(2f);
                ws.getPersistentDataContainer().set(projKey, PersistentDataType.BYTE, TAG_NO_BLOCKS);
                w.playSound(loc, Sound.ENTITY_WITHER_SHOOT, 1f, 1f);
            }
            case FIREBALL -> {
                // yield 4 = siła wybuchu TNT w Minecraft
                LargeFireball fb = p.launchProjectile(LargeFireball.class, dir.clone().multiply(1.2));
                fb.setIsIncendiary(false);
                fb.setYield(4f);
                fb.getPersistentDataContainer().set(projKey, PersistentDataType.BYTE, TAG_FIREBALL);
                w.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
            }
            case SONIC_BOOM -> {
                Set<UUID> hit = new HashSet<>();
                for (int i = 1; i <= 16; i++) {
                    Location pt = eye.clone().add(dir.clone().multiply(i));
                    w.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0, 0, 0, 0);
                    for (Entity en : w.getNearbyEntities(pt, 1.2, 1.2, 1.2)) {
                        if (validTarget(en, p) && hit.add(en.getUniqueId())) {
                            LivingEntity le = (LivingEntity) en;
                            le.damage(12, p);
                            le.setVelocity(le.getVelocity().add(dir.clone().multiply(1.2).setY(0.4)));
                        }
                    }
                }
                w.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
            }
            case DRAGON_BREATH -> {
                for (double d = 1; d <= 9; d += 0.7) {
                    Location pt = eye.clone().add(dir.clone().multiply(d));
                    double spread = 0.2 + d * 0.06;
                    w.spawnParticle(Particle.FLAME, pt, 8, spread, spread, spread, 0.02);
                    w.spawnParticle(Particle.SMOKE, pt, 3, spread, spread, spread, 0.01);
                }
                for (LivingEntity le : enemies(p, 10)) {
                    Vector to = le.getBoundingBox().getCenter().subtract(eye.toVector());
                    if (to.lengthSquared() < 0.01) continue;
                    if (to.normalize().dot(dir) > 0.8) {
                        le.damage(7, p);
                        le.setFireTicks(100);
                    }
                }
                w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.4f);
            }
            case LIGHTNING -> {
                RayTraceResult r = w.rayTrace(eye, dir, 30, FluidCollisionMode.NEVER, true, 0.5,
                        en -> validTarget(en, p));
                Location target = r != null
                        ? r.getHitPosition().toLocation(w)
                        : eye.clone().add(dir.clone().multiply(20));
                w.strikeLightning(target);
            }
            case CHAIN_LIGHTNING -> {
                LivingEntity first = targetEntity(p, 25);
                if (first == null) {
                    double best = Double.MAX_VALUE;
                    for (LivingEntity near : enemies(p, 15)) {
                        double d = near.getLocation().distanceSquared(loc);
                        if (d < best) {
                            best = d;
                            first = near;
                        }
                    }
                }
                if (first == null) return false;
                Set<UUID> hitSet = new HashSet<>();
                LivingEntity cur = first;
                Location from = eye;
                for (int i = 0; i < 5 && cur != null; i++) {
                    hitSet.add(cur.getUniqueId());
                    Location to = cur.getEyeLocation();
                    line(from, to, Particle.ELECTRIC_SPARK);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, to, 20, 0.3, 0.5, 0.3, 0.1);
                    cur.damage(7, p);
                    from = to;
                    LivingEntity next = null;
                    double best = Double.MAX_VALUE;
                    for (Entity en : cur.getNearbyEntities(7, 7, 7)) {
                        if (!validTarget(en, p) || hitSet.contains(en.getUniqueId())) continue;
                        double d = en.getLocation().distanceSquared(cur.getLocation());
                        if (d < best) {
                            best = d;
                            next = (LivingEntity) en;
                        }
                    }
                    cur = next;
                }
                w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.6f);
            }
            case METEOR -> {
                RayTraceResult r = w.rayTrace(eye, dir, 40, FluidCollisionMode.NEVER, true, 0.5,
                        en -> validTarget(en, p));
                Location target;
                if (r != null) {
                    target = r.getHitPosition().toLocation(w);
                } else {
                    Location far = eye.clone().add(dir.clone().multiply(30));
                    RayTraceResult down = w.rayTraceBlocks(far, new Vector(0, -1, 0), 60, FluidCollisionMode.NEVER, true);
                    target = down != null ? down.getHitPosition().toLocation(w) : far;
                }
                meteor(p, target);
                w.playSound(loc, Sound.ENTITY_WITHER_SHOOT, 1f, 0.5f);
            }
            case SHOCKWAVE -> {
                for (LivingEntity le : enemies(p, 8)) {
                    Vector away = le.getLocation().toVector().subtract(loc.toVector());
                    if (away.lengthSquared() < 0.01) away = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                    le.setVelocity(away.normalize().multiply(1.8).setY(0.6));
                    le.damage(3, p);
                }
                w.spawnParticle(Particle.EXPLOSION, loc.clone().add(0, 1, 0), 3, 1.0, 0.2, 1.0, 0);
                ring(loc, 3, Particle.CLOUD, 30);
                ring(loc, 6, Particle.CLOUD, 50);
                w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
            }
            case SLAM -> {
                for (LivingEntity le : enemies(p, 5)) {
                    le.damage(8, p);
                    le.setVelocity(le.getVelocity().setY(0.9));
                }
                w.spawnParticle(Particle.EXPLOSION, loc, 3, 1.5, 0.1, 1.5, 0);
                w.spawnParticle(Particle.CLOUD, loc, 40, 2.5, 0.1, 2.5, 0.05);
                w.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.6f);
            }
            case COBWEB -> {
                List<Block> placed = new ArrayList<>();
                for (LivingEntity le : enemies(p, 7)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2));
                    for (int dy = 0; dy <= 1; dy++) {
                        Block b = le.getLocation().getBlock().getRelative(0, dy, 0);
                        if (b.getType().isAir()) {
                            b.setType(Material.COBWEB);
                            placed.add(b);
                            webs.add(b);
                        }
                    }
                    w.spawnParticle(Particle.CRIT, le.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.05);
                }
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    for (Block b : placed) {
                        if (b.getType() == Material.COBWEB) b.setType(Material.AIR);
                        webs.remove(b);
                    }
                }, 100L);
                w.playSound(loc, Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.7f);
            }
            case DARKNESS -> {
                for (LivingEntity le : enemies(p, 8)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 120, 0));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 0));
                    w.spawnParticle(Particle.SMOKE, le.getEyeLocation(), 20, 0.3, 0.3, 0.3, 0.02);
                }
                ring(loc, 4, Particle.SMOKE, 40);
                w.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1f);
            }
            case VORTEX -> {
                for (LivingEntity le : enemies(p, 8)) {
                    Vector pull = loc.toVector().subtract(le.getLocation().toVector());
                    if (pull.lengthSquared() < 0.1) continue;
                    le.setVelocity(pull.normalize().multiply(1.1).setY(0.3));
                    w.spawnParticle(Particle.PORTAL, le.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.3);
                }
                w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
            }
            case TORNADO -> {
                for (LivingEntity le : enemies(p, 6)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 4));
                    le.damage(3, p);
                    w.spawnParticle(Particle.CLOUD, le.getLocation(), 30, 0.4, 1.0, 0.4, 0.05);
                }
                for (int i = 0; i < 40; i++) {
                    double ang = i * 0.5;
                    double rad = 1 + i * 0.07;
                    w.spawnParticle(Particle.CLOUD,
                            loc.clone().add(Math.cos(ang) * rad, i * 0.1, Math.sin(ang) * rad), 1, 0, 0, 0, 0);
                }
                w.playSound(loc, Sound.ENTITY_PHANTOM_FLAP, 1f, 0.6f);
            }
            case INFERNO -> {
                for (LivingEntity le : enemies(p, 8)) {
                    le.setFireTicks(160);
                    le.damage(4, p);
                    w.spawnParticle(Particle.FLAME, le.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.03);
                }
                ring(loc, 3, Particle.FLAME, 30);
                ring(loc, 6, Particle.FLAME, 50);
                ring(loc, 8, Particle.LAVA, 30);
                w.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1f, 0.6f);
            }
            case RADAR -> {
                for (Entity en : p.getNearbyEntities(30, 30, 30)) {
                    if (!validTarget(en, p)) continue;
                    ((LivingEntity) en).addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false, false));
                }
                ring(loc, 3, Particle.END_ROD, 30);
                ring(loc, 6, Particle.END_ROD, 50);
                w.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
            }
            case FREEZE -> {
                for (LivingEntity le : enemies(p, 7)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 3));
                    le.setFreezeTicks(240);
                    w.spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
                }
                w.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 60, 3, 0.5, 3, 0.02);
                w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
            }
            case STUN -> {
                for (LivingEntity le : enemies(p, 6)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 6));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 3));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 1));
                    le.setVelocity(new Vector(0, 0, 0));
                    w.spawnParticle(Particle.CRIT, le.getEyeLocation().add(0, 0.4, 0), 20, 0.3, 0.2, 0.3, 0.1);
                }
                ring(loc, 6, Particle.ELECTRIC_SPARK, 40);
                w.playSound(loc, Sound.BLOCK_ANVIL_LAND, 1f, 0.8f);
            }
            case WITHER_ROT -> {
                for (LivingEntity le : enemies(p, 8)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 2));
                    w.spawnParticle(Particle.SOUL, le.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.02);
                }
                ring(loc, 4, Particle.SOUL, 40);
                w.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 1f, 0.8f);
            }
            case LIFESTEAL -> {
                List<LivingEntity> list = enemies(p, 7);
                if (list.isEmpty()) return false;
                double healed = 0;
                for (LivingEntity le : list) {
                    le.damage(6, p);
                    healed += 6;
                    line(le.getEyeLocation(), p.getEyeLocation(), Particle.DAMAGE_INDICATOR);
                }
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + Math.min(healed, 12)));
                w.spawnParticle(Particle.HEART, loc.clone().add(0, 1.2, 0), 10, 0.4, 0.4, 0.4, 0);
                w.playSound(loc, Sound.ENTITY_WITCH_DRINK, 1f, 0.7f);
            }
            case EARTHQUAKE -> {
                new BukkitRunnable() {
                    int pulse = 0;

                    @Override
                    public void run() {
                        if (!p.isOnline() || pulse >= 6) {
                            cancel();
                            return;
                        }
                        pulse++;
                        Location c = p.getLocation();
                        World cw = c.getWorld();
                        cw.spawnParticle(Particle.BLOCK, c, 60, 3, 0.2, 3, Material.DIRT.createBlockData());
                        ring(c, 4, Particle.CLOUD, 30);
                        ring(c, 8, Particle.CLOUD, 50);
                        cw.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.5f);
                        for (LivingEntity le : enemies(p, 8)) {
                            le.damage(3, p);
                            le.setVelocity(le.getVelocity().setY(0.5));
                        }
                    }
                }.runTaskTimer(this, 0L, 10L);
            }
            case HEAL -> {
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + 10));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                w.spawnParticle(Particle.HEART, loc.clone().add(0, 1.2, 0), 10, 0.4, 0.4, 0.4, 0);
                w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            }
            case SHIELD -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1));
                w.playSound(loc, Sound.ITEM_ARMOR_EQUIP_GOLD, 1f, 1f);
            }
            case BERSERK -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0));
                w.spawnParticle(Particle.ANGRY_VILLAGER, loc.clone().add(0, 2, 0), 8, 0.4, 0.3, 0.4, 0);
                w.playSound(loc, Sound.ENTITY_RAVAGER_ROAR, 1f, 1f);
            }
            case INVISIBILITY -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0, false, false, true));
                w.spawnParticle(Particle.CLOUD, loc.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
            }
        }
        return true;
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("unequip")) {
            if (!(s instanceof Player p)) {
                s.sendMessage("Tylko dla graczy.");
                return true;
            }
            Ability a = getEquipped(p);
            if (a == null) {
                p.sendMessage(Component.text("Nie masz założonego kryształu.", NamedTextColor.RED));
                return true;
            }
            setEquipped(p, null);
            giveItem(p, createGem(a));
            p.sendMessage(Component.text("Zdjęto kryształ: " + a.displayName, NamedTextColor.YELLOW));
            return true;
        }

        if (!s.hasPermission("gems.admin")) {
            s.sendMessage(Component.text("Brak uprawnień.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            if (s instanceof Player p) openMenu(p);
            else s.sendMessage("Tylko dla graczy.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            loadData();
            s.sendMessage(Component.text("Przeładowano config i data.yml.", NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("dropped")) {
            StringBuilder got = new StringBuilder();
            StringBuilder left = new StringBuilder();
            for (Ability a : Ability.values()) {
                StringBuilder sb = dropped.contains(a) ? got : left;
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.displayName);
            }
            s.sendMessage(Component.text("Już wypadły (" + dropped.size() + "/" + Ability.values().length + "): "
                    + (got.length() == 0 ? "-" : got), NamedTextColor.RED));
            s.sendMessage(Component.text("Jeszcze mogą wypaść: " + (left.length() == 0 ? "-" : left),
                    NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") && args.length >= 2) {
            if (args[1].equalsIgnoreCase("all")) {
                dropped.clear();
                saveData();
                s.sendMessage(Component.text("Zresetowano wszystkie dropy - każdy kryształ może znów wypaść raz.",
                        NamedTextColor.GREEN));
                return true;
            }
            Ability a = parse(args[1]);
            if (a == null) {
                s.sendMessage(Component.text("Nieznana umiejętność.", NamedTextColor.RED));
                return true;
            }
            dropped.remove(a);
            saveData();
            s.sendMessage(Component.text("Kryształ " + a.displayName + " może znów wypaść.", NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("give") && args.length >= 2) {
            Ability a = parse(args[1]);
            if (a == null) {
                s.sendMessage(Component.text("Nieznana umiejętność.", NamedTextColor.RED));
                return true;
            }
            Player target;
            if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
            else target = s instanceof Player p ? p : null;
            if (target == null) {
                s.sendMessage(Component.text("Nie znaleziono gracza.", NamedTextColor.RED));
                return true;
            }
            giveItem(target, createGem(a));
            s.sendMessage(Component.text("Dano kryształ " + a.displayName + " graczowi " + target.getName(),
                    NamedTextColor.GREEN));
            return true;
        }

        s.sendMessage(Component.text("/gem | /gem give <ability> [gracz] | /gem unequip | /gem dropped | "
                + "/gem reset <ability|all> | /gem reload", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("gems.admin");
        if (args.length == 1) {
            out.add("unequip");
            if (admin) out.addAll(List.of("give", "reload", "dropped", "reset"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && admin) {
            for (Ability a : Ability.values()) out.add(a.name().toLowerCase(Locale.ROOT));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset") && admin) {
            out.add("all");
            for (Ability a : Ability.values()) out.add(a.name().toLowerCase(Locale.ROOT));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && admin) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

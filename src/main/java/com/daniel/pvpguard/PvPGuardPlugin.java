package com.daniel.pvpguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PvPGuardPlugin extends JavaPlugin implements Listener {

    // Estados
    private final Map<UUID, Long> protectUntil = new HashMap<>();
    private final Map<UUID, List<Long>> deathTimes = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Integer> bossBarTasks = new HashMap<>();

    // Config
    private int thresholdKills;
    private long windowMillis;
    protected long protectionMillis; // usado por /proteger test
    private boolean disableDamageBothWays;

    // BossBar
    private boolean enableBossBar;
    private String bossbarTitle;
    private BarColor bossbarColor;
    private BarStyle bossbarStyle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PvPGuard habilitado.");
    }

    private void loadConfig() {
        reloadConfig();
        thresholdKills = getConfig().getInt("threshold_kills", 3);
        windowMillis    = getConfig().getLong("window_minutes", 30) * 60_000L;
        protectionMillis= getConfig().getLong("protection_minutes", 15) * 60_000L;
        disableDamageBothWays = getConfig().getBoolean("disable_damage_both_ways", true);

        enableBossBar  = getConfig().getBoolean("enable_bossbar", true);
        bossbarTitle   = getConfig().getString("bossbar_title", "&aEscudo PvP: {mm}:{ss}");
        try { bossbarColor = BarColor.valueOf(getConfig().getString("bossbar_color", "GREEN").toUpperCase()); }
        catch (Exception e) { bossbarColor = BarColor.GREEN; }
        try { bossbarStyle = BarStyle.valueOf(getConfig().getString("bossbar_style", "SEGMENTED_10").toUpperCase()); }
        catch (Exception e) { bossbarStyle = BarStyle.SEGMENTED_10; }
    }

    // ====== Lógica de activación por muertes PvP ======
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!(e.getEntity().getKiller() instanceof Player)) return; // solo PvP
        Player victim = e.getEntity();
        long now = System.currentTimeMillis();
        UUID vid = victim.getUniqueId();

        deathTimes.putIfAbsent(vid, new ArrayList<>());
        List<Long> deaths = deathTimes.get(vid);
        deaths.add(now);
        // Limpiar las que salen de la ventana
        deaths.removeIf(t -> now - t > windowMillis);

        if (deaths.size() >= thresholdKills) {
            deaths.clear();
            applyProtection(victim);
        }
    }

    // Cancelar daño si hay protección (víctima y, opcionalmente, atacante)
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) attacker = p;

        boolean victimProtected   = protectUntil.containsKey(victim.getUniqueId())
                && protectUntil.get(victim.getUniqueId()) > System.currentTimeMillis();
        boolean attackerProtected = attacker != null
                && protectUntil.containsKey(attacker.getUniqueId())
                && protectUntil.get(attacker.getUniqueId()) > System.currentTimeMillis();

        if (disableDamageBothWays) {
            if (victimProtected || attackerProtected) e.setCancelled(true);
        } else {
            if (victimProtected) e.setCancelled(true);
        }
    }

    // Reenganchar bossbar si el jugador entra aún protegido
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Long end = protectUntil.get(id);
        if (enableBossBar && end != null && end > System.currentTimeMillis()) {
            showOrRefreshBossBar(e.getPlayer(), end);
        }
    }

    private void applyProtection(Player p) {
        long endAt = System.currentTimeMillis() + protectionMillis;
        protectUntil.put(p.getUniqueId(), endAt);
        String msg = getConfig().getString("on_activate_message",
                "&aTienes un escudo protector de PvP por {minutes} minutos.")
                .replace("{minutes}", String.valueOf(getConfig().getInt("protection_minutes", 15)));
        p.sendMessage(colorize(msg));

        if (enableBossBar) showOrRefreshBossBar(p, endAt);

        // Programar expiración
        new BukkitRunnable() {
            @Override public void run() { expireProtection(p.getUniqueId()); }
        }.runTaskLater(this, protectionMillis / 50L);
    }

    private void expireProtection(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            p.sendMessage(colorize(getConfig().getString("on_expire_message",
                    "&cTu escudo protector ha terminado.")));
        }
        protectUntil.remove(id);
        hideBossBar(id);
    }

    // ===== BossBar =====
    private void showOrRefreshBossBar(Player p, long endAt) {
        UUID id = p.getUniqueId();

        BossBar bar = bossBars.get(id);
        if (bar == null) {
            bar = Bukkit.createBossBar(colorize(bossbarTitle), bossbarColor, bossbarStyle);
            bar.addPlayer(p);
            bar.setVisible(true);
            bossBars.put(id, bar);
        } else {
            if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            bar.setVisible(true);
        }

        Integer prev = bossBarTasks.remove(id);
        if (prev != null) Bukkit.getScheduler().cancelTask(prev);

        // Variables capturadas por el lambda: deben ser final
        final UUID fid        = id;
        final BossBar fbar    = bar;
        final long total      = protectionMillis;
        final String template = bossbarTitle;

        int updater = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Long end = protectUntil.get(fid);
            if (end == null) { hideBossBar(fid); return; }

            long now = System.currentTimeMillis();
            long remaining = Math.max(0, end - now);

            String title = template
                    .replace("&", "§")
                    .replace("{mm}", twoDigits((int) ((remaining / 1000) / 60)))
                    .replace("{ss}", twoDigits((int) ((remaining / 1000) % 60)))
                    .replace("{minutes}", String.valueOf(getConfig().getLong("protection_minutes", 15)));
            fbar.setTitle(title);

            double progress = Math.max(0d, Math.min(1d, (double) remaining / (double) total));
            fbar.setProgress(progress);

            if (progress > 0.5)       fbar.setColor(BarColor.GREEN);
            else if (progress > 0.2)  fbar.setColor(BarColor.YELLOW);
            else                      fbar.setColor(BarColor.RED);

            if (remaining <= 0) expireProtection(fid);
        }, 0L, 20L).getTaskId();

        bossBarTasks.put(id, updater);
    }

    private void hideBossBar(UUID id) {
        BossBar bar = bossBars.remove(id);
        if (bar != null) bar.removeAll();
        Integer t = bossBarTasks.remove(id);
        if (t != null) Bukkit.getScheduler().cancelTask(t);
    }

    // ===== Comandos =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores pueden usar este comando.");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(ChatColor.YELLOW + "Usa /proteger off | /proteger test [segundos]");
            return true;
        }
        if (args[0].equalsIgnoreCase("off")) {
            protectUntil.remove(p.getUniqueId());
            hideBossBar(p.getUniqueId());
            p.sendMessage(colorize(getConfig().getString("on_manual_off_message",
                    "&6Protección PvP desactivada manualmente.")));
            return true;
        }
        if (args[0].equalsIgnoreCase("test")) {
            int secs = 60;
            if (args.length >= 2) {
                try { secs = Math.max(5, Integer.parseInt(args[1])); } catch (Exception ignored) {}
            }
            long old = protectionMillis;
            protectionMillis = secs * 1000L;
            applyProtection(p);
            protectionMillis = old;
            p.sendMessage(ChatColor.GREEN + "Escudo de prueba activado por " + secs + "s.");
            return true;
        }
        p.sendMessage(ChatColor.RED + "Uso: /proteger off | /proteger test [segundos]");
        return true;
    }

    // ===== Utils =====
    private static String colorize(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String twoDigits(int n) {
        return (n < 10 ? "0" : "") + n;
    }
}

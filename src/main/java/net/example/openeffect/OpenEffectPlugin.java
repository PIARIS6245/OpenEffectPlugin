package net.example.openeffect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class OpenEffectPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Set<UUID> enabled = new HashSet<>();
    private final Set<UUID> owners  = new HashSet<>();
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> opCache = new HashSet<>(); // ★起動時の管理者キャッシュ

    private DisplayManager displays;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();

        // --- 起動時に op.json を読み込んでキャッシュ＋ownersに追加 ---
        opCache.clear();
        for (OfflinePlayer op : Bukkit.getOperators()) {
            if (op.getUniqueId() != null) {
                opCache.add(op.getUniqueId());
                owners.add(op.getUniqueId());
            }
        }
        saveAll();

        displays = new DisplayManager(this);

        registerCommand("open", this);
        registerCommand("openeffect", this);

        displays.ensureAllTargets();
        for (Player v : Bukkit.getOnlinePlayers()) applyVisibilityFor(v);

        int period = Math.max(1, getConfig().getInt("updateTicks", 1));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            displays.ensureAllTargets();
            displays.updateAll();
        }, period, period);

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("OpenEffect enabled 1.2.0");
    }

    private void registerCommand(String name, TabExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) throw new IllegalStateException("command " + name + " not found");
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
    }

    @Override
    public void onDisable() {
        if (displays != null) displays.despawnAll();
        saveAll();
        getLogger().info("OpenEffect disabled.");
    }

    // ===== Events =====
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        displays.ensureTarget(e.getPlayer());
        applyVisibilityFor(e.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        displays.removeTarget(e.getPlayer().getUniqueId());
        saveAll();
    }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("updateOnMove", true)) return;
        if (e.getFrom().toVector().distanceSquared(e.getTo().toVector()) < 1.0E-6) return;
        displays.updateOne(e.getPlayer());
    }

    // ===== Visibility =====
    public boolean isOwner(UUID id) { return owners.contains(id); }
    public boolean isMember(UUID id) { return members.contains(id); }
    public boolean canUse(Player p) {
        return (isOwner(p.getUniqueId()) || isMember(p.getUniqueId())) && enabled.contains(p.getUniqueId());
    }
    public boolean canManage(Player p) { return isOwner(p.getUniqueId()); }

    private void applyVisibilityFor(Player viewer) {
        displays.applyVisibility(viewer, canUse(viewer));
    }

    // ===== Commands =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("open")) return handleOpen(sender, args);
        if (cmd.getName().equalsIgnoreCase("openeffect")) return handleManage(sender, args);
        return false;
    }

    private boolean handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color("&cゲーム内で実行してください。"));
            return true;
        }
        Player p = (Player) sender;
        if (!isOwner(p.getUniqueId()) && !isMember(p.getUniqueId())) {
            p.sendMessage(color("&cあなたにはこのコマンドを実行できる権限がありません!"));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("effect")) {
            p.sendMessage(color("&c使い方: /open effect <on|off>"));
            return true;
        }
        if (args[1].equalsIgnoreCase("on")) {
            enabled.add(p.getUniqueId()); saveAll();
            displays.applyVisibility(p, true);
            p.sendMessage(color("&aエフェクト表示を ON にしました!"));
        } else if (args[1].equalsIgnoreCase("off")) {
            enabled.remove(p.getUniqueId()); saveAll();
            displays.applyVisibility(p, false);
            p.sendMessage(color("&eエフェクト表示を OFF にしました!"));
        } else {
            p.sendMessage(color("&c使い方: /open effect <on|off>"));
        }
        return true;
    }

    private boolean handleManage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color("&cゲーム内で実行してください。"));
            return true;
        }
        Player p = (Player) sender;
        UUID me = p.getUniqueId();

        if (!isOwner(me) && !isMember(me)) {
            p.sendMessage(color("&cあなたにはこのコマンドを実行できる権限がありません!"));
            return true;
        }

        // --- owners/members/all ---
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "members": return cmdMembers(p);
                case "owners":  return cmdOwners(p);
                case "all":     return cmdAll(p);
            }
        }

        // --- add/remove はオーナー限定 ---
        if (args.length >= 1 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            if (!canManage(p)) {
                p.sendMessage(color("&cあなたにはこのコマンドを実行できる権限がありません!"));
                return true;
            }
        }

        // add
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return cmdAdd(p, args[1], args[2]);
        }

        // remove
        if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
            return cmdRemove(p, args[1], args[2]);
        }

        p.sendMessage(color("&c使い方: /openeffect <add|remove|members|owners|all> ..."));
        return true;
    }

    private boolean cmdAdd(Player sender, String role, String name) {
        UUID target = resolvePlayerUUID(name);
        if (target == null) {
            sender.sendMessage(color("&cこのプレイヤー名は存在していません!"));
            return true;
        }
        if (role.equalsIgnoreCase("owner")) {
            if (target.equals(sender.getUniqueId())) {
                sender.sendMessage(color("&c自分を追加することはできません!"));
                return true;
            }
            owners.add(target); members.remove(target); saveAll();
            sender.sendMessage(color("&a" + name + " をオーナーとして追加に成功しました!"));
        } else if (role.equalsIgnoreCase("member")) {
            members.add(target); saveAll();
            sender.sendMessage(color("&a" + name + " をメンバーとして追加に成功しました!"));
        } else {
            sender.sendMessage(color("&c使い方: /openeffect add <member|owner> <プレイヤー名>"));
        }
        return true;
    }

    private boolean cmdRemove(Player sender, String role, String name) {
        UUID target = resolvePlayerUUID(name);
        if (target == null) {
            sender.sendMessage(color("&cこのプレイヤー名は存在していません!"));
            return true;
        }
        if (role.equalsIgnoreCase("owner")) {
            if (opCache.contains(target)) {
                sender.sendMessage(color("&cこの人は管理者なので削除できません!"));
                return true;
            }
            if (target.equals(sender.getUniqueId())) {
                sender.sendMessage(color("&c自分自身を削除することはできません!"));
                return true;
            }
            if (owners.remove(target)) {
                saveAll();
                sender.sendMessage(color("&aオーナー " + name + " の削除に成功しました!"));
            } else sender.sendMessage(color("&cオーナーに " + name + " はいません!"));
        } else if (role.equalsIgnoreCase("member")) {
            if (members.remove(target)) {
                enabled.remove(target); saveAll();
                sender.sendMessage(color("&aメンバー " + name + " の削除に成功しました!"));
            } else sender.sendMessage(color("&cメンバーに " + name + " はいません!"));
        } else {
            sender.sendMessage(color("&c使い方: /openeffect remove <member|owner> <プレイヤー名>"));
        }
        return true;
    }

    private boolean cmdMembers(Player p) {
        p.sendMessage(color("&eMembers:"));
        for (UUID id : members) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            p.sendMessage(color(op.getName() != null ? op.getName() : id.toString()));
        }
        return true;
    }
    private boolean cmdOwners(Player p) {
        p.sendMessage(color("&eOwners:"));
        for (UUID id : owners) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            p.sendMessage(color(op.getName() != null ? op.getName() : id.toString()));
        }
        return true;
    }
    private boolean cmdAll(Player p) { cmdOwners(p); cmdMembers(p); return true; }

    private UUID resolvePlayerUUID(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on.getUniqueId();
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off != null && (off.hasPlayedBefore() || off.isOnline())) return off.getUniqueId();
        return null;
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void loadAll() {
        enabled.clear(); owners.clear(); members.clear();
        enabled.addAll(getConfig().getStringList("enabled").stream().map(UUID::fromString).collect(Collectors.toSet()));
        owners.addAll(getConfig().getStringList("owners").stream().map(UUID::fromString).collect(Collectors.toSet()));
        members.addAll(getConfig().getStringList("members").stream().map(UUID::fromString).collect(Collectors.toSet()));
    }
    private void saveAll() {
        getConfig().set("enabled", enabled.stream().map(UUID::toString).collect(Collectors.toList()));
        getConfig().set("owners", owners.stream().map(UUID::toString).collect(Collectors.toList()));
        getConfig().set("members", members.stream().map(UUID::toString).collect(Collectors.toList()));
        saveConfig();
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (c.getName().equalsIgnoreCase("open")) {
            if (args.length == 1) return Collections.singletonList("effect");
            if (args.length == 2 && "effect".equalsIgnoreCase(args[0])) return Arrays.asList("on","off");
        }
        if (c.getName().equalsIgnoreCase("openeffect")) {
            if (!(s instanceof Player)) return Collections.emptyList();
            Player p = (Player) s; boolean mgr = canManage(p);
            if (args.length == 1) {
                List<String> base = new ArrayList<>(Arrays.asList("members","owners","all"));
                if (mgr) base.addAll(Arrays.asList("add","remove"));
                return base;
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")))
                return mgr ? Arrays.asList("member","owner") : Collections.emptyList();
        }
        return Collections.emptyList();
    }
}

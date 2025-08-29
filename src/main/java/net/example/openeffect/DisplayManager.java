package net.example.openeffect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * ターゲットごとに行単位の ArmorStand を「上端から下方向へ」積む。
 * 可視性は viewer 単位＆「自分自身の行は常に非表示」。
 */
public class DisplayManager {

    private final OpenEffectPlugin core;

    // targetUUID -> 行ごとのArmorStand
    private final Map<UUID, List<ArmorStand>> displays = new HashMap<>();
    // 直近の描画内容（変化検知）
    private final Map<UUID, List<String>> lastLines = new HashMap<>();

    // config
    private final double offRight, offForward;
    private final double topUp, stepDown;
    private final boolean showPlayerName;
    private final String language;

    public DisplayManager(OpenEffectPlugin plugin) {
        this.core = plugin;
        var cfg = plugin.getConfig();
        this.offRight    = cfg.getDouble("offsetRight",   0.0);
        this.offForward  = cfg.getDouble("offsetForward", 0.0);
        this.topUp       = cfg.getDouble("box.topUp",     1.90);
        this.stepDown    = cfg.getDouble("box.step",      0.20);
        this.showPlayerName = cfg.getBoolean("showPlayerName", false);
        this.language    = cfg.getString("language", "ja");
    }

    public void ensureAllTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) ensureTarget(p);
    }

    public void ensureTarget(Player target) {
        displays.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        lastLines.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        if (displays.get(target.getUniqueId()).isEmpty()) {
            ArmorStand as = spawnLine(target, 0);
            displays.get(target.getUniqueId()).add(as);
            // 可視性適用（自分には非表示）
            reapplyVisibilityForTarget(target.getUniqueId(), as);
        }
    }

    public void removeTarget(UUID targetId) {
        removeAllLines(targetId);
        displays.remove(targetId);
        lastLines.remove(targetId);
    }

    public void despawnAll() {
        for (UUID id : new ArrayList<>(displays.keySet())) removeAllLines(id);
        displays.clear();
        lastLines.clear();
    }

    private void removeAllLines(UUID id) {
        List<ArmorStand> list = displays.get(id);
        if (list != null) {
            for (ArmorStand as : list) if (as != null && !as.isDead()) as.remove();
            list.clear();
        }
    }

    public void updateAll() {
        for (Player target : Bukkit.getOnlinePlayers()) updateOne(target);
        displays.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastLines.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    public void updateOne(Player target) {
        if (target == null || !target.isOnline()) return;

        List<String> lines = buildEffectLines(target);
        UUID id = target.getUniqueId();

        if (!lines.equals(lastLines.getOrDefault(id, Collections.emptyList()))) {
            removeAllLines(id);
            List<ArmorStand> list = displays.computeIfAbsent(id, k -> new ArrayList<>());
            for (int i = 0; i < lines.size(); i++) {
                ArmorStand as = spawnLine(target, i);
                as.customName(Component.text(lines.get(i)));
                list.add(as);
                reapplyVisibilityForTarget(id, as); // 新規行の可視性
            }
            lastLines.put(id, new ArrayList<>(lines));
        } else {
            List<ArmorStand> list = displays.get(id);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    ArmorStand as = list.get(i);
                    if (as == null || as.isDead()) continue;
                    as.teleport(linePos(target, i));
                }
            }
        }
    }

    /**
     * viewer の可視性を一括反映。
     * show=true でも「viewer自身のターゲット行」は常に非表示にする。
     */
    public void applyVisibility(Player viewer, boolean show) {
        UUID vid = viewer.getUniqueId();
        for (Map.Entry<UUID, List<ArmorStand>> e : displays.entrySet()) {
            UUID targetId = e.getKey();
            boolean showThis = show && !vid.equals(targetId) && core.canUse(viewer);
            for (ArmorStand as : e.getValue()) {
                if (as == null || as.isDead()) continue;
                if (showThis) viewer.showEntity(core, as);
                else viewer.hideEntity(core, as);
            }
        }
    }

    /** 特定ターゲットの行を、全 viewer に対して再適用（自分には非表示） */
    private void reapplyVisibilityForTarget(UUID targetId, ArmorStand as) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean show = core.canUse(v) && !v.getUniqueId().equals(targetId);
            if (show) v.showEntity(core, as);
            else v.hideEntity(core, as);
        }
    }

    // ==== 位置計算：上端から下に積む ====
    private Location linePos(Player target, int index) {
        Location top = topPos(target);
        return top.add(0, -stepDown * index, 0);
    }

    private ArmorStand spawnLine(Player target, int index) {
        Location pos = linePos(target, index);
        World w = target.getWorld();
        return w.spawn(pos, ArmorStand.class, ent -> {
            ent.setMarker(true);
            ent.setInvisible(true);
            ent.setSilent(true);
            ent.setGravity(false);
            ent.setCustomNameVisible(true);
            ent.customName(Component.text(""));
            ent.setVisibleByDefault(false); // viewer単位
        });
    }

    private Location topPos(Player target) {
        Location eye = target.getEyeLocation();
        Vector right = rightOf(eye);
        Vector fwd   = forwardFlat(eye);
        return eye.clone()
                .add(right.multiply(offRight))
                .add(fwd.multiply(offForward))
                .add(0, topUp, 0);
    }

    private Vector forwardFlat(Location eye) {
        Vector fwd = eye.getDirection().clone(); fwd.setY(0);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(0,0,1);
        return fwd.normalize();
    }
    private Vector rightOf(Location eye) {
        Vector fwd = forwardFlat(eye);
        return new Vector(-fwd.getZ(), 0, fwd.getX()).normalize();
    }

    // ==== 表示テキスト ====
    private List<String> buildEffectLines(Player target) {
        List<String> out = new ArrayList<>();
        if (showPlayerName) out.add(target.getName());

        Collection<PotionEffect> effects = target.getActivePotionEffects();
        if (effects.isEmpty()) {
            out.add("（効果なし）");
            return out;
        }
        for (PotionEffect eff : effects) {
            String type = effectName(eff);
            int lv = eff.getAmplifier() + 1;
            int sec = Math.max(0, eff.getDuration() / 20);
            String m = String.format("%d:%02d", sec / 60, sec % 60);
            out.add(type + " " + roman(lv) + " " + m);
        }
        return out;
        // ※ 行が多すぎてネームタグと被る場合は config の box.topUp / box.step を調整
    }

    private String effectName(PotionEffect eff) {
        String key = eff.getType().getName();
        switch (key) {
            case "SPEED": return "移動速度";
            case "SLOW": return "移動低下";
            case "FAST_DIGGING": return "採掘速度";
            case "SLOW_DIGGING": return "採掘低下";
            case "INCREASE_DAMAGE": return "攻撃力上昇";
            case "HEAL": return "即時回復";
            case "HARM": return "即時ダメージ";
            case "JUMP": return "跳躍力上昇";
            case "REGENERATION": return "再生";
            case "DAMAGE_RESISTANCE": return "耐性";
            case "FIRE_RESISTANCE": return "耐火";
            case "WATER_BREATHING": return "水中呼吸";
            case "INVISIBILITY": return "透明化";
            case "NIGHT_VISION": return "暗視";
            case "HUNGER": return "空腹";
            case "WEAKNESS": return "弱体化";
            case "POISON": return "毒";
            case "WITHER": return "衰弱";
            case "HEALTH_BOOST": return "体力増強";
            case "ABSORPTION": return "衝撃吸収";
            case "SATURATION": return "満腹度回復";
            case "GLOWING": return "発光";
            case "LEVITATION": return "浮遊";
            case "LUCK": return "幸運";
            case "UNLUCK": return "不運";
            case "CONDUIT_POWER": return "コンジットパワー";
            case "DOLPHINS_GRACE": return "イルカの好意";
            case "BAD_OMEN": return "不吉な予感";
            case "HERO_OF_THE_VILLAGE": return "村の英雄";
            default: return key;
        }
    }

    private String roman(int n) {
        String[] r = {"","I","II","III","IV","V","VI","VII","VIII","IX","X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}

// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package cubexcraft.mobileperf;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/**
 * CubeX Mobile keeps the frame rate on phones (owner 26.10: «минимум 60 кадров, но не в ущерб отображению»).
 *
 * <p>Where the world is heavy (the spawn with its machines and NPCs) a phone's CPU cannot draw 8 chunks at 60 FPS; on open
 * ground it can. So the render distance follows the frame rate, within the bounds the app writes to
 * config/cubexcraft-mobileperf.properties: below 52 FPS for a few seconds — one chunk less (then fewer far entities);
 * 70 FPS and more for 12 s — one step back up, never above the app's ceiling. After a step up that did not hold, the level
 * stays for 3 minutes, so the picture does not swing. The render distance only changes what is drawn: the chunk cache
 * radius comes from the server, so nothing has to be loaded again when it grows back.</p>
 *
 * <p>Idle unless the app started the game ({@code -Dcubex.mobile=true}); any error of its own switches it off, never the game.
 * Every 30 s the frame rate goes to the log (the developers read it from the test builds).</p>
 */
@Mod(value = MobilePerf.ID, dist = Dist.CLIENT)
public final class MobilePerf {
    public static final String ID = "cubexcraft_mobileperf";
    static final Logger LOG = LoggerFactory.getLogger("CubeX MobilePerf");

    public MobilePerf(IEventBus modBus) {
        if (!Boolean.getBoolean("cubex.mobile")) {
            LOG.info("not started by CubeX Mobile: idle");
            return;
        }
        Path dir = FMLPaths.CONFIGDIR.get();
        Governor g = new Governor(dir.resolve("cubexcraft-mobileperf.properties"), dir.resolve("cubexcraft-mobileperf.state"));
        NeoForge.EVENT_BUS.addListener(g::onTick);
        LOG.info("on: render distance {}..{}, entity distance {}..{}, keep {}..{} FPS",
            g.minRd, g.cfgMaxRd, g.minEnt, g.cfgMaxEnt, g.low, g.high);
    }

    static final class Governor {
        // bounds from the app (defaults when the file is missing)
        final int cfgMaxRd;
        final int minRd;
        final double cfgMaxEnt;
        final double minEnt;
        final int low;
        final int high;
        private final Path stateFile;

        private int maxRd;               // ceiling of this session (the app's, or what the player set in the settings)
        private double maxEnt;
        private int ourRd = -1;          // the values this mod set last: anything else was the player
        private double ourEnt = -1;
        private final int[] fps = new int[12];
        private int samples;
        private int ticks;
        private boolean inWorld;
        private long worldSince;
        private long quietUntil;          // no decision before this (a change is still showing its effect)
        private long lastUpAt;
        private long holdUntil;          // no step up before this (the last one did not hold)
        private boolean failed;
        // the 30 s report
        private long reportAt;
        private int repMin = Integer.MAX_VALUE;
        private int repMax;
        private long repSum;
        private int repN;
        private int repDown;
        private int repUp;

        Governor(Path config, Path state) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(config)) {
                p.load(in);
            } catch (Exception e) {
                LOG.info("no {} — defaults", config.getFileName());
            }
            this.stateFile = state;
            int max = intOf(p, "maxRenderDistance", 8);
            cfgMaxRd = clamp(max, 2, 32);
            minRd = clamp(intOf(p, "minRenderDistance", Math.max(4, max - 3)), 2, cfgMaxRd);
            cfgMaxEnt = clamp(doubleOf(p, "maxEntityDistance", 0.75), 0.5, 5.0);
            minEnt = clamp(doubleOf(p, "minEntityDistance", 0.5), 0.5, cfgMaxEnt);
            low = intOf(p, "lowFps", 52);
            high = Math.max(low + 10, intOf(p, "highFps", 70));
        }

        void onTick(ClientTickEvent.Post e) {
            if (failed) return;
            try {
                tick();
            } catch (Throwable t) {
                failed = true;
                LOG.warn("switched off after an error (the game goes on)", t);
            }
        }

        private void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                inWorld = false;
                return;
            }
            long now = System.currentTimeMillis();
            if (!inWorld) enter(mc, now);
            if (++ticks % 20 != 0) return;   // client ticks are 20 a second whatever the frame rate

            // the player changed a distance in the game's settings: from now on that is the ceiling
            int rd = mc.options.renderDistance().get();
            if (rd != ourRd) {
                maxRd = Math.max(minRd, rd);
                ourRd = rd;
                LOG.info("render distance {} set in the settings — the ceiling now", rd);
            }
            double ent = mc.options.entityDistanceScaling().get();
            if (Math.abs(ent - ourEnt) > 1e-6) {
                maxEnt = Math.max(minEnt, ent);
                ourEnt = ent;
            }

            if (mc.screen != null || now - worldSince < 12_000) return;   // menus and the first seconds (loading) do not count
            int f = mc.getFps();
            fps[samples % fps.length] = f;
            samples++;
            report(now, f, rd, ent);
            if (now < quietUntil) return;

            // a frame cap (the game's limit or vsync at the screen's rate) below the thresholds: measure against the cap
            int cap = mc.options.framerateLimit().get();
            if (cap >= 260) cap = Integer.MAX_VALUE;
            if (mc.options.enableVsync().get()) cap = Math.min(cap, Math.max(30, mc.getWindow().getRefreshRate()));
            int lowNow = Math.min(low, cap - 12);
            int highNow = Math.min(high, cap - 3);
            if (median(4) < lowNow) {
                stepDown(mc, now, f);
            } else if (now >= holdUntil && samples >= fps.length && minOf(fps.length) >= highNow) {
                stepUp(mc, now, f);
            }
        }

        private void enter(Minecraft mc, long now) {
            inWorld = true;
            worldSince = now;
            samples = 0;
            maxRd = cfgMaxRd;
            maxEnt = cfgMaxEnt;
            // start where the last session settled (the spawn is where one joins), not at the ceiling
            int start = clamp(lastStable(), minRd, maxRd);
            setRd(mc, start);
            setEnt(mc, maxEnt);
            quietUntil = now + 12_000;
            LOG.info("in the world: render distance {} (ceiling {})", start, maxRd);
        }

        private void stepDown(Minecraft mc, long now, int f) {
            int rd = mc.options.renderDistance().get();
            if (rd > minRd) {
                setRd(mc, rd - 1);
                LOG.info("{} FPS: render distance {} -> {}", f, rd, rd - 1);
            } else if (mc.options.entityDistanceScaling().get() > minEnt + 1e-6) {
                double ent = Math.max(minEnt, mc.options.entityDistanceScaling().get() - 0.25);
                setEnt(mc, ent);
                LOG.info("{} FPS: entity distance -> {}", f, ent);
            } else {
                quietUntil = now + 10_000;
                return;   // at the floor: nothing more that would not spoil the picture
            }
            repDown++;
            // a step up that did not hold: stay here a while instead of swinging
            if (now - lastUpAt < 40_000) holdUntil = now + 180_000;
            quietUntil = now + 6_000;
            samples = 0;
            remember(mc.options.renderDistance().get());
        }

        private void stepUp(Minecraft mc, long now, int f) {
            double ent = mc.options.entityDistanceScaling().get();
            int rd = mc.options.renderDistance().get();
            if (ent < maxEnt - 1e-6) {
                setEnt(mc, Math.min(maxEnt, ent + 0.25));
                LOG.info("{} FPS: entity distance -> {}", f, Math.min(maxEnt, ent + 0.25));
            } else if (rd < maxRd) {
                setRd(mc, rd + 1);
                LOG.info("{} FPS: render distance {} -> {}", f, rd, rd + 1);
            } else {
                return;
            }
            repUp++;
            lastUpAt = now;
            quietUntil = now + 8_000;
            samples = 0;
            remember(mc.options.renderDistance().get());
        }

        private void setRd(Minecraft mc, int v) {
            ourRd = v;
            mc.options.renderDistance().set(v);
        }

        private void setEnt(Minecraft mc, double v) {
            ourEnt = v;
            mc.options.entityDistanceScaling().set(v);
        }

        private void report(long now, int f, int rd, double ent) {
            repMin = Math.min(repMin, f);
            repMax = Math.max(repMax, f);
            repSum += f;
            repN++;
            if (reportAt == 0) reportAt = now + 30_000;
            if (now < reportAt) return;
            LOG.info("30 s: FPS avg {} min {} max {} | render distance {} (ceiling {}) entity {} | steps down {} up {}",
                repSum / Math.max(1, repN), repMin, repMax, rd, maxRd, ent, repDown, repUp);
            reportAt = now + 30_000;
            repMin = Integer.MAX_VALUE;
            repMax = 0;
            repSum = 0;
            repN = 0;
            repDown = 0;
            repUp = 0;
        }

        private int median(int n) {
            int k = Math.min(n, samples);
            if (k == 0) return Integer.MAX_VALUE;
            int[] a = new int[k];
            for (int i = 0; i < k; i++) a[i] = fps[(samples - 1 - i) % fps.length];
            Arrays.sort(a);
            return a[k / 2];
        }

        private int minOf(int n) {
            int m = Integer.MAX_VALUE;
            for (int i = 0; i < Math.min(n, samples); i++) m = Math.min(m, fps[(samples - 1 - i) % fps.length]);
            return m;
        }

        private int lastStable() {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(stateFile)) {
                p.load(in);
                return Integer.parseInt(p.getProperty("renderDistance", String.valueOf(cfgMaxRd)).trim());
            } catch (Exception e) {
                return cfgMaxRd;
            }
        }

        private void remember(int rd) {
            Properties p = new Properties();
            p.setProperty("renderDistance", String.valueOf(rd));
            try (OutputStream out = Files.newOutputStream(stateFile)) {
                p.store(out, "CubeX Mobile: where the render distance settled last time");
            } catch (Exception ignored) {
                // not worth a word: next session starts at the ceiling
            }
        }

        private static int intOf(Properties p, String k, int d) {
            try {
                return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim());
            } catch (NumberFormatException e) {
                return d;
            }
        }

        private static double doubleOf(Properties p, String k, double d) {
            try {
                return Double.parseDouble(p.getProperty(k, String.valueOf(d)).trim());
            } catch (NumberFormatException e) {
                return d;
            }
        }

        private static int clamp(int v, int a, int b) {
            return Math.max(a, Math.min(b, v));
        }

        private static double clamp(double v, double a, double b) {
            return Math.max(a, Math.min(b, v));
        }
    }
}

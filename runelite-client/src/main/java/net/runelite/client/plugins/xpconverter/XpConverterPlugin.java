//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package net.runelite.client.plugins.xpconverter;

import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.xptracker.XpTrackerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
        name = "XP Converter",
        description = "Enable the XP Converter panel",
        tags = {"panel", "xp"},
        enabledByDefault = false
)
@PluginDependency(XpTrackerPlugin.class)
public class XpConverterPlugin extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(XpConverterPlugin.class);
    @Inject
    private ClientUI ui;
    @Inject
    private Client client;
    @Inject
    private SkillIconManager skillIconManager;
    @Inject
    private ClientToolbar clientToolbar;
    private NavigationButton uiNavigationButton;
    private XpConverterPanel uiPanel;
    private boolean dirty;

    public XpConverterPlugin() {
    }

    protected void startUp() throws Exception {
        BufferedImage icon = ImageUtil.loadImageResource(this.getClass(), "icon.png");
        this.uiPanel = new XpConverterPanel(this.client, this.skillIconManager);
        this.uiNavigationButton = NavigationButton.builder().tooltip("XP Converter").icon(icon).priority(4).panel(this.uiPanel).build();
        this.clientToolbar.addNavigation(this.uiNavigationButton);
    }

    protected void shutDown() throws Exception {
        this.clientToolbar.removeNavigation(this.uiNavigationButton);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (this.dirty) {
            this.uiPanel.uiCalculator.calculate();
            this.dirty = false;
        }

    }

    @Subscribe
    public void onStatChanged(StatChanged statChanged) {
        this.dirty = true;
    }
}

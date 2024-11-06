//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package net.runelite.client.plugins.xpconverter;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

public class XpConverterPanel extends PluginPanel {
    final XpConverter uiCalculator;

    XpConverterPanel(Client client, SkillIconManager iconManager) {
        this.getScrollPane().setVerticalScrollBarPolicy(22);
        this.setBorder(new EmptyBorder(10, 10, 10, 10));
        this.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = 1;
        c.weightx = 1.0;
        c.gridx = 0;
        c.gridy = 0;
        UIConverterInputArea uiInput = new UIConverterInputArea();
        uiInput.setBorder(new EmptyBorder(15, 0, 15, 0));
        uiInput.setBackground(ColorScheme.DARK_GRAY_COLOR);
        this.uiCalculator = new XpConverter(client, uiInput);
        MaterialTabGroup skillsPanel = new MaterialTabGroup();
        skillsPanel.setLayout(new GridLayout(0, 1, 0, 1));
        Skill[] values = Skill.values();
        Skill[] ordered = new Skill[values.length];
        int index = 0;
        Skill[] var9 = values;
        int var10 = values.length;

        int var11;
        Skill skill;
        for(var11 = 0; var11 < var10; ++var11) {
            skill = var9[var11];
            if (skill != Skill.NONE) {
                ordered[index++] = skill;
            }
        }

        ordered[index] = Skill.NONE;
        var9 = ordered;
        var10 = ordered.length;

        for(var11 = 0; var11 < var10; ++var11) {
            skill = var9[var11];
            MaterialTabGroup skillPanel = new MaterialTabGroup();
            skillPanel.setLayout(new GridLayout(1, 0, 1, 1));
            ImageIcon icon = new ImageIcon(iconManager.getSkillImage(skill, true));
            MaterialTab tab = new MaterialTab(icon, skillPanel, null);
            JLabel levelText = new JLabel("Level: 99", 0);
            JLabel xpText = new JLabel("200,000,000", 0);
            levelText.setFont(FontManager.getRunescapeSmallFont());
            xpText.setFont(FontManager.getRunescapeSmallFont());
            tab.setMaximumSize(new Dimension(16, 16));
            tab.setPreferredSize(new Dimension(16, 16));
            tab.setSize(new Dimension(16, 16));
            tab.setOnSelectEvent(() -> {
                return false;
            });
            skillPanel.setMaximumSize(new Dimension(16, 16));
            skillPanel.setPreferredSize(new Dimension(16, 16));
            skillPanel.setSize(new Dimension(16, 16));
            skillPanel.addTab(tab);
            skillPanel.add(levelText);
            skillPanel.add(xpText);
            skillsPanel.add(skillPanel);
            this.uiCalculator.xpLabels.put(skill, xpText);
            this.uiCalculator.levelLavels.put(skill, levelText);
        }

        this.uiCalculator.calculate();
        this.add(uiInput, c);
        ++c.gridy;
        this.add(this.uiCalculator, c);
        ++c.gridy;
        this.add(skillsPanel, c);
        ++c.gridy;
    }
}

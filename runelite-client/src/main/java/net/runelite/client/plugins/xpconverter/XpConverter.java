//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package net.runelite.client.plugins.xpconverter;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.ui.DynamicGridLayout;

public class XpConverter extends JPanel {
    private static final DecimalFormat XP_FORMAT = new DecimalFormat();
    Map<Skill, JLabel> xpLabels = new HashMap();
    Map<Skill, JLabel> levelLavels = new HashMap();
    private Client client;
    private UIConverterInputArea uiInput;
    private int currentXpRate = 1;
    private int targetXpRate;

    XpConverter(Client client, UIConverterInputArea uiInput) {
        this.targetXpRate = this.currentXpRate + 1;
        this.client = client;
        this.uiInput = uiInput;
        this.setLayout(new DynamicGridLayout(1, 0, 5, 0));
        uiInput.currentXpRate.addActionListener((e) -> {
            this.onFieldCurrentLevelUpdated();
            uiInput.targetXpRate.requestFocusInWindow();
        });
        uiInput.targetXpRate.addActionListener((e) -> {
            this.onFieldTargetLevelUpdated();
            uiInput.targetXpRate.requestFocusInWindow();
        });
    }

    void calculate() {
        double multiplication = (double)this.targetXpRate / (double)this.currentXpRate;
        double overallXp = 0.0;
        int overallLvl = 0;
        Skill[] var6 = Skill.values();
        int var7 = var6.length;

        for(int var8 = 0; var8 < var7; ++var8) {
            Skill skill = var6[var8];
            if (skill != Skill.NONE) {
                JLabel xpText = this.xpLabels.get(skill);
                JLabel lvlText = this.levelLavels.get(skill);
                double oldXp = this.client.getSkillExperience(skill);
                double newXp = Math.min(2.0E8, Math.max(skill == Skill.HITPOINTS ? 1154.0 : 0.0, oldXp * multiplication));
                int newLevel = Math.min(getMaxLevel(skill), Experience.getLevelForXp((int)newXp));
                overallXp += newXp;
                overallLvl += newLevel;
                lvlText.setText("Level: " + newLevel);
                xpText.setText("" + XP_FORMAT.format((int)newXp));
            }
        }

        Skill skill = Skill.NONE;
        JLabel xpText = this.xpLabels.get(skill);
        JLabel lvlText = this.levelLavels.get(skill);
        lvlText.setText("" + overallLvl);
        xpText.setText("" + XP_FORMAT.format((int)overallXp));
    }

    private void updateInputFields() {
        this.uiInput.setCurrentXpRate(this.currentXpRate);
        this.uiInput.setTargetXpRate(this.targetXpRate);
        this.calculate();
    }

    private void onFieldCurrentLevelUpdated() {
        this.currentXpRate = enforceXpRateBounds(this.uiInput.getCurrentXpRate());
        this.updateInputFields();
    }

    private void onFieldTargetLevelUpdated() {
        this.targetXpRate = enforceXpRateBounds(this.uiInput.getTargetXpRate());
        this.updateInputFields();
    }

    private static int getMaxLevel(Skill skill) {
        return 99;
    }

    private static int enforceXpRateBounds(int input) {
        return Math.min(1000, Math.max(1, input));
    }
}

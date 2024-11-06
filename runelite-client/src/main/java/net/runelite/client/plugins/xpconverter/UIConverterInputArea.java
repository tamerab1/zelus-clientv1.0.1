//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package net.runelite.client.plugins.xpconverter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.FlatTextField;

public class UIConverterInputArea extends JPanel {
    JTextField currentXpRate;
    JTextField targetXpRate;

    UIConverterInputArea() {
        this.setLayout(new GridLayout(2, 2, 7, 7));
        this.currentXpRate = this.addComponent("Current XP Rate");
        this.targetXpRate = this.addComponent("Target XP Rate");
        this.setCurrentXpRate(1);
        this.setTargetXpRate(2);
    }

    int getCurrentXpRate() {
        return this.getInput(this.currentXpRate);
    }

    void setCurrentXpRate(int value) {
        this.setInput(this.currentXpRate, value);
    }

    int getTargetXpRate() {
        return this.getInput(this.targetXpRate);
    }

    void setTargetXpRate(Object value) {
        this.setInput(this.targetXpRate, value);
    }

    private int getInput(JTextField field) {
        try {
            return Integer.parseInt(field.getText());
        } catch (NumberFormatException var3) {
            return 0;
        }
    }

    private void setInput(JTextField field, Object value) {
        field.setText(String.valueOf(value));
    }

    private JTextField addComponent(String label) {
        JPanel container = new JPanel();
        container.setLayout(new BorderLayout());
        JLabel uiLabel = new JLabel(label);
        FlatTextField uiInput = new FlatTextField();
        uiInput.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        uiInput.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
        uiInput.setBorder(new EmptyBorder(5, 7, 5, 7));
        uiLabel.setFont(FontManager.getRunescapeSmallFont());
        uiLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
        uiLabel.setForeground(Color.WHITE);
        container.add(uiLabel, "North");
        container.add(uiInput, "Center");
        this.add(container);
        return uiInput.getTextField();
    }
}

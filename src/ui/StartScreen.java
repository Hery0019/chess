package ui;

import engine.Pieces;
import game.GameConfig;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Pre-game configuration screen. Emits an immutable {@link GameConfig} via
 * the callback; performs no game logic itself.
 */
public final class StartScreen extends JPanel {

    /** Combo-box entry: minutes per side, {@link GameConfig#NO_CLOCK} for untimed. */
    private record TimeControl(int minutes) {
        @Override public String toString() {
            return minutes == GameConfig.NO_CLOCK ? "No clock (untimed)" : minutes + " min";
        }
    }

    public StartScreen(Consumer<GameConfig> onStart) {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel("Chess");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));

        JRadioButton humanVsAi = new JRadioButton("Human vs AI", true);
        JRadioButton aiVsAi = new JRadioButton("AI vs AI");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(humanVsAi);
        modeGroup.add(aiVsAi);

        JRadioButton playWhite = new JRadioButton("White", true);
        JRadioButton playBlack = new JRadioButton("Black");
        ButtonGroup sideGroup = new ButtonGroup();
        sideGroup.add(playWhite);
        sideGroup.add(playBlack);

        // Side choice is meaningless in AI-vs-AI: disabled, not hidden, so the
        // layout stays stable and the causality is visible to the user.
        Runnable syncSideEnabled = () -> {
            boolean human = humanVsAi.isSelected();
            playWhite.setEnabled(human);
            playBlack.setEnabled(human);
        };
        humanVsAi.addActionListener(e -> syncSideEnabled.run());
        aiVsAi.addActionListener(e -> syncSideEnabled.run());

        JComboBox<TimeControl> timeControl = new JComboBox<>(new TimeControl[]{
                new TimeControl(GameConfig.NO_CLOCK),
                new TimeControl(3), new TimeControl(5), new TimeControl(10),
                new TimeControl(15), new TimeControl(30)
        });
        timeControl.setSelectedIndex(3);   // 10 min

        JSpinner depth = new JSpinner(new SpinnerNumberModel(4, 1, 5, 1));

        JButton start = new JButton("Start Game");
        start.addActionListener(e -> onStart.accept(new GameConfig(
                humanVsAi.isSelected() ? GameConfig.Mode.HUMAN_VS_AI : GameConfig.Mode.AI_VS_AI,
                playWhite.isSelected() ? Pieces.WHITE : Pieces.BLACK,
                ((TimeControl) timeControl.getSelectedItem()).minutes(),
                (Integer) depth.getValue())));

        int row = 0;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        add(title, c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; add(new JLabel("Mode:"), c);
        JPanel modePanel = new JPanel();
        modePanel.add(humanVsAi); modePanel.add(aiVsAi);
        c.gridx = 1; add(modePanel, c);

        row++;
        c.gridx = 0; c.gridy = row; add(new JLabel("Play as:"), c);
        JPanel sidePanel = new JPanel();
        sidePanel.add(playWhite); sidePanel.add(playBlack);
        c.gridx = 1; add(sidePanel, c);

        row++;
        c.gridx = 0; c.gridy = row; add(new JLabel("Time per side:"), c);
        c.gridx = 1; add(timeControl, c);

        row++;
        c.gridx = 0; c.gridy = row; add(new JLabel("AI depth (1 = fast/weak, 5 = slow/strong):"), c);
        c.gridx = 1; add(depth, c);

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(18, 8, 6, 8);
        add(start, c);

        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    }
}

package com.osrsadvisor.plugin;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import net.runelite.client.ui.ColorScheme;

/**
 * The per-card Fastest/Balanced/AFK selector: three rounded segmented pills. All three
 * lens plans arrive in the payload, so a pick is answered locally - no fetch. A lens
 * whose chain did not converge (null in the plans map) renders disabled.
 *
 * Labels are abbreviated because three full names do not fit across a ~180px row at the
 * RuneScape small font; the tooltip carries the full name.
 */
class LensPills extends JPanel
{
	static final String[] LENSES = {"Fastest", "Balanced", "AFK"};
	private static final String[] LABELS = {"Fast", "Bal", "AFK"};

	LensPills(Map<String, RecommendationsResponse.Plan> plans, String selected,
		Consumer<String> onPick)
	{
		setLayout(new GridLayout(1, LENSES.length, 3, 0));
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		ButtonGroup group = new ButtonGroup();
		for (int i = 0; i < LENSES.length; i++)
		{
			String lens = LENSES[i];
			boolean available = plans != null && plans.get(lens) != null;
			JToggleButton pill = pill(LABELS[i]);
			pill.setToolTipText(available ? lens : lens + " — no route for this lens");
			pill.setEnabled(available);
			pill.setSelected(lens.equals(selected));
			pill.setForeground(pill.isSelected() ? Color.WHITE : CardPanel.DIM);
			pill.addItemListener(e ->
				pill.setForeground(pill.isSelected() ? Color.WHITE : CardPanel.DIM));
			pill.addActionListener(e -> onPick.accept(lens));
			group.add(pill);
			add(pill);
		}
		// BoxLayout parents stretch children to their maximum; cap it to a row.
		Dimension max = new Dimension(CardPanel.TEXT_PX, 20);
		setMaximumSize(max);
		setPreferredSize(max);
	}

	private static JToggleButton pill(String label)
	{
		JToggleButton b = new JToggleButton(label)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				if (isSelected())
				{
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				}
				else if (getModel().isRollover() && isEnabled())
				{
					g2.setColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}
				else
				{
					g2.setColor(ColorScheme.DARK_GRAY_COLOR);
				}
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		b.setFont(CardPanel.BODY);
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		b.setRolloverEnabled(true);
		return b;
	}
}

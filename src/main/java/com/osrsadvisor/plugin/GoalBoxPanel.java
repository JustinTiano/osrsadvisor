package com.osrsadvisor.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One goal box under the step list. AdvisorPanel feeds it client-side stats via
 * {@link #updateStat} on every xp drop, so the box moves live with no server round
 * trip. At the gate it flips to a green "Met" and waits for the next real refresh to
 * advance to the next row.
 *
 * Two faces. The Slayer/Combat pair is a centered totem: big skill icon, the levels
 * under it ("9/18"), a thin bar symbolizing the xp still to go, then the quest # and
 * name. The Priority AFK box keeps the icon-left layout with the raw xp figure and its
 * method line, since a 1.2M-xp gap reads better as a number than as "1/75".
 *
 * Not a CardPanel: no header row, no chevron, never collapses. Same rounded paint and
 * emboss, because the boxes sit in the same column as the cards.
 */
class GoalBoxPanel extends JPanel
{
	/** The blown-up level readout on the pair boxes. */
	private static final Font BIG_NUM = FontManager.getDefaultBoldFont().deriveFont(16f);

	private final long targetXp;
	private final long startXp;
	private final int targetLevel;
	private final boolean levelOnly;
	private final JLabel number = new JLabel();
	private final XpBar bar;
	private long liveXp;
	private int liveLevel;

	GoalBoxPanel(RecommendationsResponse.Goal goal, BufferedImage icon, String header,
		int textPx, boolean levelOnly)
	{
		this.targetXp = goal.targetXp;
		this.startXp = goal.startXp;
		this.targetLevel = goal.targetLevel;
		this.levelOnly = levelOnly;
		this.liveXp = goal.startXp;
		this.liveLevel = goal.startLevel;
		this.bar = levelOnly ? new XpBar() : null;

		setOpaque(false);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));

		if (levelOnly)
		{
			buildTotem(goal, icon, textPx);
		}
		else
		{
			buildWide(goal, icon, header, textPx);
		}
		refresh();
	}

	/** Icon on top, levels below it, the bar, then the quest - all centered. */
	private void buildTotem(RecommendationsResponse.Goal goal, BufferedImage icon,
		int textPx)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		if (icon != null)
		{
			JLabel iconLabel = new JLabel(new ImageIcon(icon));
			iconLabel.setAlignmentX(CENTER_ALIGNMENT);
			add(iconLabel);
			add(Box.createVerticalStrut(3));
		}
		number.setFont(BIG_NUM);
		number.setAlignmentX(CENTER_ALIGNMENT);
		add(number);
		add(Box.createVerticalStrut(3));
		bar.setAlignmentX(CENTER_ALIGNMENT);
		add(bar);
		add(Box.createVerticalStrut(3));
		JLabel quest = new JLabel("<html><body style='width:" + textPx
			+ "px; text-align:center; color:" + CardPanel.hex(CardPanel.DIM) + ";'>#"
			+ goal.pos + " " + CardPanel.esc(goal.name) + "</body></html>");
		quest.setFont(CardPanel.SMALL);
		quest.setAlignmentX(CENTER_ALIGNMENT);
		add(quest);
	}

	/** Icon left, lines right - the Priority AFK box. */
	private void buildWide(RecommendationsResponse.Goal goal, BufferedImage icon,
		String header, int textPx)
	{
		setLayout(new BorderLayout(4, 0));
		if (icon != null)
		{
			JLabel iconLabel = new JLabel(new ImageIcon(icon));
			iconLabel.setVerticalAlignment(JLabel.TOP);
			add(iconLabel, BorderLayout.WEST);
		}

		JPanel lines = new JPanel();
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		lines.setOpaque(false);
		if (header != null)
		{
			JLabel head = new JLabel(header);
			head.setFont(CardPanel.SMALL);
			head.setForeground(CardPanel.DIM);
			head.setAlignmentX(LEFT_ALIGNMENT);
			lines.add(head);
		}
		number.setFont(CardPanel.NUM);
		number.setAlignmentX(LEFT_ALIGNMENT);
		lines.add(number);

		JLabel quest = CardPanel.html("#" + goal.pos + " " + CardPanel.esc(goal.name),
			textPx, CardPanel.DIM);
		quest.setFont(CardPanel.SMALL);
		quest.setAlignmentX(LEFT_ALIGNMENT);
		lines.add(quest);

		if (goal.method != null)
		{
			JLabel m = CardPanel.html("AFK now: " + CardPanel.esc(goal.method.name)
				+ " <font color='" + CardPanel.hex(CardPanel.DIM) + "'>"
				+ goal.method.from + "→" + goal.method.to + "</font>", textPx, null);
			m.setFont(CardPanel.SMALL);
			m.setAlignmentX(LEFT_ALIGNMENT);
			lines.add(m);
			if (goal.method.location != null && !goal.method.location.isEmpty())
			{
				setToolTipText(goal.method.location
					+ (goal.method.travel != null && !goal.method.travel.isEmpty()
						? " — " + goal.method.travel : ""));
			}
		}
		add(lines, BorderLayout.CENTER);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		CardPanel.paintCard(g, this);
		super.paintComponent(g);
	}

	/** Client-side stats, merged max against what the server response started from. */
	void updateStat(long xp, int level)
	{
		liveXp = Math.max(liveXp, xp);
		liveLevel = Math.max(liveLevel, level);
		refresh();
	}

	private void refresh()
	{
		long needed = Math.max(0, targetXp - Math.max(startXp, liveXp));
		boolean met = needed == 0 || liveLevel >= targetLevel;
		if (met)
		{
			number.setText("Met ✓");
			number.setForeground(CardPanel.GREEN);
		}
		else if (levelOnly)
		{
			number.setText(liveLevel + "/" + targetLevel);
			number.setForeground(Color.WHITE);
		}
		else
		{
			number.setText(String.format("%,d xp", needed));
			number.setForeground(Color.WHITE);
		}
		if (bar != null)
		{
			bar.set(targetXp <= 0 ? 1f : (float) liveXp / targetXp, met);
		}
	}

	/** Three pixels of "how much is left": xp toward the gate, green once through it. */
	private static final class XpBar extends JComponent
	{
		private float fraction;
		private boolean met;

		XpBar()
		{
			setPreferredSize(new Dimension(10, 3));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 3));
		}

		void set(float fraction, boolean met)
		{
			this.fraction = Math.max(0f, Math.min(1f, fraction));
			this.met = met;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(met ? CardPanel.GREEN : CardPanel.AMBER);
			g.fillRect(0, 0, Math.round(getWidth() * (met ? 1f : fraction)),
				getHeight());
		}
	}
}

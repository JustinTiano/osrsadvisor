package com.osrsadvisor.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * Shopping mode's view: a transparent stack of up to two rounded cards - "GE"
 * (the merged buy list, with its est total and market warnings) and
 * "Untradeables" (what the GE can't supply). Either card only appears when it
 * has content, so a window with nothing untradeable reads as one clean list.
 *
 * Deliberately terse throughout: each row is "qty × item", already net of the
 * bank (the merge happened server-side), so the GE card reads as a literal
 * order. Which step wants an item, acceptable alternatives, and how to source
 * an untradeable all live behind the row's dim ⓘ tooltip rather than on screen.
 */
class ShoppingPanel extends JPanel
{
	/** Name column inside a name-left/cost-right row. */
	private static final int NAME_PX = 104;

	ShoppingPanel(RecommendationsResponse.Shopping s)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		JPanel ge = geCard(s);
		JPanel un = untradeablesCard(s.notBuyable);
		if (ge != null)
		{
			add(ge);
		}
		if (un != null)
		{
			if (ge != null)
			{
				add(Box.createVerticalStrut(4));   // same gap as the card list
			}
			add(un);
		}
		if (ge == null && un == null)
		{
			JPanel empty = card();
			line(empty, "Nothing to buy.", CardPanel.TEXT_PX, CardPanel.DIM,
				CardPanel.BODY);
			add(empty);
		}
	}

	/** One rounded card, painted like every other raised surface in the sidebar. */
	private static JPanel card()
	{
		JPanel p = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				CardPanel.paintCard(g, this);
				super.paintComponent(g);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 11));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static JPanel head(String title, String rightText, Color rightColor)
	{
		JPanel head = new JPanel(new BorderLayout(4, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel left = new JLabel(title);
		left.setFont(CardPanel.TITLE);
		left.setForeground(Color.WHITE);
		head.add(left, BorderLayout.CENTER);
		if (rightText != null)
		{
			JLabel right = new JLabel(rightText);
			right.setFont(CardPanel.NUM);
			right.setForeground(rightColor);
			head.add(right, BorderLayout.EAST);
		}
		return head;
	}

	/** The buy list proper; null when the window needs nothing from the GE. */
	private static JPanel geCard(RecommendationsResponse.Shopping s)
	{
		boolean anyRows = s.rows != null && !s.rows.isEmpty();
		boolean anyCoins = s.questCoinsGp != null && s.questCoinsGp > 0;
		if (!anyRows && !anyCoins)
		{
			return null;
		}
		JPanel card = card();
		card.add(head("GE", anyRows ? "est " + Fmt.gp(s.totalGp) : null,
			Color.WHITE));
		if (s.affordability != null && !"ok".equals(s.affordability))
		{
			line(card, CardPanel.esc(s.affordability), CardPanel.TEXT_PX,
				CardPanel.AMBER, CardPanel.BODY);
		}
		if (s.partial)
		{
			line(card, "<i>some training needs are unknown for this lens</i>",
				CardPanel.TEXT_PX, CardPanel.DIM, CardPanel.SMALL);
		}
		if (anyRows)
		{
			card.add(Box.createVerticalStrut(5));
			for (RecommendationsResponse.ShopRow r : s.rows)
			{
				addBuyRow(card, r);
			}
		}
		if (anyCoins)
		{
			card.add(Box.createVerticalStrut(2));
			JLabel coins = CardPanel.html("+ " + String.format("%,d", s.questCoinsGp)
				+ " gp coins", CardPanel.TEXT_PX, CardPanel.DIM);
			coins.setFont(CardPanel.BODY);
			coins.setAlignmentX(Component.LEFT_ALIGNMENT);
			coins.setToolTipText("Coins these quests need directly.");
			card.add(coins);
		}
		if (s.warnings != null && !s.warnings.isEmpty())
		{
			card.add(Box.createVerticalStrut(4));
			for (String w : s.warnings)
			{
				line(card, CardPanel.esc(w), CardPanel.TEXT_PX, CardPanel.AMBER,
					CardPanel.SMALL);
			}
		}
		return card;
	}

	/** What the GE can't supply; null when everything in the window is buyable. */
	private static JPanel untradeablesCard(
		List<RecommendationsResponse.NotBuyable> nb)
	{
		if (nb == null || nb.isEmpty())
		{
			return null;
		}
		JPanel card = card();
		card.add(head("Untradeables", Integer.toString(nb.size()), CardPanel.DIM));
		card.add(Box.createVerticalStrut(5));
		for (RecommendationsResponse.NotBuyable n : nb)
		{
			StringBuilder tip = new StringBuilder();
			if (n.refs != null && !n.refs.isEmpty())
			{
				tip.append("For: ").append(CardPanel.esc(forText(n.refs)));
			}
			if (n.how != null && !n.how.isEmpty())
			{
				if (tip.length() > 0)
				{
					tip.append("<br>");
				}
				tip.append("↳ ").append(CardPanel.esc(n.how));
			}
			String tipText = tip.length() == 0 ? null
				: "<html><body style='width:220px'>" + tip + "</body></html>";
			// Prose the parser couldn't turn into an item keeps its own words
			// and no quantity - "1 × <sentence>" would be nonsense.
			String name = n.check ? CardPanel.esc(n.item)
				: String.format("%,d", n.need) + " × " + CardPanel.esc(n.item);
			if (tipText != null)
			{
				name += " <font color='" + CardPanel.hex(CardPanel.DIM)
					+ "'>ⓘ</font>";
			}
			JLabel row = CardPanel.html(name, CardPanel.TEXT_PX, Color.WHITE);
			row.setFont(CardPanel.BODY);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setToolTipText(tipText);
			card.add(row);
			card.add(Box.createVerticalStrut(2));
		}
		return card;
	}

	/** "1 × Purple dye ⓘ" left, "500 gp" right; the detail lives in the tooltip. */
	private static void addBuyRow(JPanel card, RecommendationsResponse.ShopRow r)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		String tip = rowTooltip(r);
		String name = String.format("%,d", r.qty) + " × " + CardPanel.esc(r.item);
		if (tip != null)
		{
			name += " <font color='" + CardPanel.hex(CardPanel.DIM) + "'>ⓘ</font>";
		}
		JLabel left = CardPanel.html(name, NAME_PX, Color.WHITE);
		left.setFont(CardPanel.BODY);
		JLabel right = new JLabel(Fmt.gp(r.cost) + " gp");
		right.setFont(CardPanel.BODY);
		right.setForeground(CardPanel.DIM);
		right.setVerticalAlignment(JLabel.TOP);
		// Tooltips don't bubble up from children - the whole row is the target.
		row.setToolTipText(tip);
		left.setToolTipText(tip);
		right.setToolTipText(tip);

		row.add(left, BorderLayout.CENTER);
		row.add(right, BorderLayout.EAST);
		card.add(row);
		card.add(Box.createVerticalStrut(2));
	}

	private static String rowTooltip(RecommendationsResponse.ShopRow r)
	{
		StringBuilder tip = new StringBuilder();
		if (r.refs != null && !r.refs.isEmpty())
		{
			tip.append("For: ").append(CardPanel.esc(forText(r.refs)));
		}
		if (r.note != null && !r.note.isEmpty())
		{
			if (tip.length() > 0)
			{
				tip.append("<br>");
			}
			tip.append("Note: ").append(CardPanel.esc(r.note));
		}
		if (r.choices != null && r.choices.size() > 1)
		{
			if (tip.length() > 0)
			{
				tip.append("<br>");
			}
			tip.append("Any of: ").append(CardPanel.esc(String.join(", ", r.choices)));
		}
		return tip.length() == 0 ? null
			: "<html><body style='width:220px'>" + tip + "</body></html>";
	}

	/** "Firemaking 35→40 · #7 Lost City" - train refs by level span, quest by row. */
	private static String forText(List<RecommendationsResponse.ForRef> refs)
	{
		StringBuilder out = new StringBuilder();
		for (RecommendationsResponse.ForRef ref : refs)
		{
			if (out.length() > 0)
			{
				out.append(" · ");
			}
			if ("train".equals(ref.kind))
			{
				out.append(ref.skill);
				if (ref.fromLevel != null && ref.toLevel != null)
				{
					out.append(' ').append(ref.fromLevel)
						.append('→').append(ref.toLevel);
				}
			}
			else
			{
				if (ref.pos != null)
				{
					out.append('#').append(ref.pos).append(' ');
				}
				out.append(ref.name);
			}
		}
		return out.toString();
	}

	private static void line(JPanel target, String html, int width, Color color,
		Font font)
	{
		JLabel label = CardPanel.html(html, width, color);
		label.setFont(font);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		target.add(label);
	}
}

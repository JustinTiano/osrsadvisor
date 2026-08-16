package com.osrsadvisor.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * One guide row. The training detail that used to nest under a short gate now lives on
 * its own {@link TrainCardPanel} directly above this card; a short or covered gate just
 * says so and points there via its color.
 *
 * Ticking is deliberately behind a right-click and a confirm: the guide only lists
 * unticked rows, so a mis-tick silently skips a quest. Training cards have no tick -
 * this is the only card kind that maps to a guide row.
 */
class QuestCardPanel extends CardPanel
{
	/** Item-row name column: the quantity label and the detail indent take the rest. */
	private static final int ITEM_NAME_PX = TEXT_PX - 56;

	private final RecommendationsResponse.Step step;

	QuestCardPanel(RecommendationsResponse.Step step, boolean startExpanded,
		Runnable onToggleExpand, Consumer<RecommendationsResponse.Step> onMarkDone,
		BufferedImage titleIcon)
	{
		super(startExpanded, onToggleExpand);
		this.step = step;

		// No badge at a glance - gates, items and state all live in the drop-downs,
		// so the title gets the width instead.
		String title = "#" + step.pos + "  " + esc(step.name)
			+ (step.diary ? " <font color='" + hex(DIM) + "'>(diary)</font>" : "");
		JLabel name = html(title, TEXT_PX - (titleIcon != null ? 28 : 10), null);
		name.setFont(TITLE);

		JPopupMenu popup = new JPopupMenu();
		JMenuItem tick = new JMenuItem("Mark #" + step.pos + " done…");
		tick.setFont(BODY);
		tick.addActionListener(e -> confirmTick(step, onMarkDone));
		popup.add(tick);

		assemble(icon(titleIcon), name, null, popup);
	}

	/** The title icon, in the header's west slot. Clicks fall through to the header:
	 * expand/collapse. */
	private static JLabel icon(BufferedImage titleIcon)
	{
		return titleIcon == null ? null : new JLabel(new ImageIcon(titleIcon));
	}

	private void confirmTick(RecommendationsResponse.Step step,
		Consumer<RecommendationsResponse.Step> onMarkDone)
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"Tick \"#" + step.pos + " " + step.name + "\" in the quest guide?\n"
				+ "The guide only lists unticked rows, so this skips it.",
			"Mark done", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.OK_OPTION)
		{
			onMarkDone.accept(step);
		}
	}

	// --- body -------------------------------------------------------------

	@Override
	protected void buildBody()
	{
		body.add(Box.createVerticalStrut(4));

		if (step.notes != null)
		{
			for (String note : step.notes)
			{
				addLine(body, "<i>" + esc(note) + "</i>", TEXT_PX, DIM, SMALL);
			}
		}

		if (step.prereqs != null && !step.prereqs.isEmpty())
		{
			body.add(Box.createVerticalStrut(3));
			for (RecommendationsResponse.Prereq p : step.prereqs)
			{
				Color c = "done".equals(p.status) ? GREEN
					: "planned".equals(p.status) ? AMBER : RED;
				String suffix = "planned".equals(p.status) ? " (planned above)" : "";
				addLine(body, esc(p.name) + suffix, TEXT_PX, c);
			}
		}

		addGates();
		addItems(step);
	}

	/** Gates live behind their own drop-down - the card face stays clean. */
	private void addGates()
	{
		if (step.gates == null || step.gates.isEmpty())
		{
			return;
		}
		JPanel detail = detailPanel();
		for (RecommendationsResponse.Gate g : step.gates)
		{
			addGate(detail, g);
		}
		long shortGates = step.gates.stream()
			.filter(g -> "short".equals(g.status)).count();
		boolean allPlanned = step.gates.stream()
			.filter(g -> "short".equals(g.status))
			.allMatch(g -> g.trainRef != null);
		boolean allMet = step.gates.stream().allMatch(g -> "met".equals(g.status));
		Color c = shortGates > 0 ? (allPlanned ? AMBER : RED) : allMet ? GREEN : AMBER;
		buildToggle(detail,
			step.gates.size() + (step.gates.size() == 1 ? " gate" : " gates"), c);
	}

	/** The hidden, indented container both drop-downs fill. */
	private JPanel detailPanel()
	{
		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.setBorder(BorderFactory.createEmptyBorder(2, 8, 0, 0));
		detail.setVisible(false);
		return detail;
	}

	private void addGate(JPanel target, RecommendationsResponse.Gate g)
	{
		boolean planned = g.trainRef != null;
		Color c = "met".equals(g.status) ? GREEN
			: "covered".equals(g.status) ? AMBER
			: planned ? AMBER : RED;
		StringBuilder text = new StringBuilder(esc(g.skill))
			.append(' ').append(g.have).append('/').append(g.level);
		if (g.boostable)
		{
			text.append(" <font color='").append(hex(DIM)).append("'>(boostable)</font>");
		}
		if (g.midquest)
		{
			text.append(" <font color='").append(hex(DIM)).append("'>(mid-quest)</font>");
		}
		if ("covered".equals(g.status) || ("short".equals(g.status) && planned))
		{
			text.append(" <font color='").append(hex(DIM))
				.append("'>via training above</font>");
		}
		addLine(target, text.toString(), TEXT_PX - 8, c);
	}

	private void addItems(RecommendationsResponse.Step step)
	{
		if (step.items == null)
		{
			return;
		}
		List<RecommendationsResponse.ItemReq> notable = new java.util.ArrayList<>();
		for (RecommendationsResponse.ItemReq i : step.items)
		{
			if (!"have".equals(i.status))
			{
				notable.add(i);
			}
		}
		if (notable.isEmpty())
		{
			if (!step.items.isEmpty())
			{
				body.add(Box.createVerticalStrut(3));
				addLine(body, "all " + step.items.size() + " items held", TEXT_PX, GREEN);
			}
			return;
		}

		JPanel detail = detailPanel();
		for (RecommendationsResponse.ItemReq i : notable)
		{
			detail.add(itemRow(i));
		}

		String caption = step.itemsShort > 0
			? step.itemsShort + (step.itemsShort == 1 ? " item short" : " items short")
			: notable.size() + " items to check";
		buildToggle(detail, caption, step.itemsShort > 0 ? AMBER : DIM);
	}

	/** What still needs obtaining, name left and quantity pinned right - the bank's
	 * share of the count stays out of the row, the shortfall is the row. */
	private JPanel itemRow(RecommendationsResponse.ItemReq i)
	{
		Color c = "short".equals(i.status) ? AMBER
			: "missing".equals(i.status) ? RED : DIM;
		StringBuilder name = new StringBuilder(esc(i.name));
		if (i.optional)
		{
			name.append(" <font color='").append(hex(DIM)).append("'>(optional)</font>");
		}
		else if (i.alt)
		{
			name.append(" <font color='").append(hex(DIM)).append("'>(alt)</font>");
		}
		if (i.choices != null && i.choices.size() > 1)
		{
			name.append(" <font color='").append(hex(DIM)).append("'>· one of ")
				.append(i.choices.size()).append("</font>");
		}
		String qty;
		if ("short".equals(i.status) || "missing".equals(i.status))
		{
			qty = "× " + String.format("%,d", Math.max(1, i.need - i.have));
		}
		else
		{
			// unknown/note: a parsing gap to eyeball, not a claim you lack it.
			qty = "note".equals(i.status) ? "" : "?";
		}

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel nameLabel = html(name.toString(), ITEM_NAME_PX, c);
		nameLabel.setFont(BODY);
		row.add(nameLabel, BorderLayout.CENTER);
		if (!qty.isEmpty())
		{
			JLabel qtyLabel = new JLabel(qty);
			qtyLabel.setFont(BODY);
			qtyLabel.setForeground(c);
			qtyLabel.setVerticalAlignment(JLabel.TOP);
			row.add(qtyLabel, BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			row.getPreferredSize().height));
		return row;
	}

	private void buildToggle(JPanel detail, String caption, Color color)
	{
		JLabel toggle = new JLabel("▸ " + caption);
		toggle.setFont(BODY);
		toggle.setForeground(color);
		toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean show = !detail.isVisible();
				detail.setVisible(show);
				toggle.setText((show ? "▾ " : "▸ ") + caption);
				QuestCardPanel.this.revalidate();
				QuestCardPanel.this.repaint();
			}
		});
		body.add(Box.createVerticalStrut(3));
		body.add(toggle);
		body.add(detail);
	}
}

package com.osrsadvisor.plugin;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * A first-class training step: "Train Woodcutting 35 → 36", for the quest gate named in
 * the body. The lens pills switch between the three plans already in the payload - the
 * body rebuilds locally, no fetch - and each route band renders as its own rounded
 * sub-block with hours, net gp, optional location/travel and a wiki link.
 *
 * No Mark-done here: a training step is not a guide row, there is nothing to tick -
 * it disappears on its own once the pushed xp clears the gate.
 */
class TrainCardPanel extends CardPanel
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

	private final RecommendationsResponse.Step step;
	private final Consumer<String> onLensPick;
	private String lens;

	TrainCardPanel(RecommendationsResponse.Step step, boolean startExpanded,
		String effectiveLens, Runnable onToggleExpand, Consumer<String> onLensPick,
		BufferedImage titleIcon)
	{
		super(startExpanded, onToggleExpand);
		this.step = step;
		this.onLensPick = onLensPick;
		this.lens = usableLens(effectiveLens);

		// No level range and no hours badge up here - the collapsed card names the
		// activity, the expanded body says how far it takes you and how long.
		JLabel title = html("Train " + esc(step.skill),
			TEXT_PX - (titleIcon != null ? 28 : 10), null);
		title.setFont(TITLE);
		if (titleIcon != null)
		{
			title.setIcon(new ImageIcon(titleIcon));
			title.setIconTextGap(5);
			title.setVerticalTextPosition(JLabel.CENTER);
		}

		assemble(title, null, null);
	}

	private static String level(Integer lvl)
	{
		return lvl == null ? "?" : Integer.toString(lvl);
	}

	/** The requested lens if its plan exists, else Fastest, else any non-null lens. */
	private String usableLens(String want)
	{
		if (step.plans == null)
		{
			return want;
		}
		if (want != null && step.plans.get(want) != null)
		{
			return want;
		}
		if (step.plans.get("Fastest") != null)
		{
			return "Fastest";
		}
		for (String l : LensPills.LENSES)
		{
			if (step.plans.get(l) != null)
			{
				return l;
			}
		}
		return want;
	}

	private RecommendationsResponse.Plan plan()
	{
		return step.plans == null ? null : step.plans.get(lens);
	}

	// --- body -------------------------------------------------------------

	@Override
	protected void buildBody()
	{
		body.add(Box.createVerticalStrut(4));

		StringBuilder forLine = new StringBuilder(level(step.fromLevel))
			.append(" → ").append(level(step.toLevel))
			.append(" · <i>for #")
			.append(step.forPos == null ? 0 : step.forPos).append(' ')
			.append(esc(step.forName)).append("</i>");
		if (step.boostable)
		{
			forLine.append(" (boostable)");
		}
		if (step.midquest)
		{
			forLine.append(" (mid-quest)");
		}
		addLine(body, forLine.toString(), TEXT_PX, DIM, SMALL);

		if ("uncosted".equals(step.status))
		{
			addLine(body, "needs " + xpText() + " xp — " + esc(step.note), TEXT_PX, DIM);
			return;
		}
		if (!"route".equals(step.status))
		{
			addLine(body, esc(step.error != null ? step.error : "no route"), TEXT_PX, RED);
			return;
		}

		body.add(Box.createVerticalStrut(4));
		body.add(new LensPills(step.plans, lens, this::pickLens));
		body.add(Box.createVerticalStrut(4));

		RecommendationsResponse.Plan p = plan();
		if (p == null)
		{
			addLine(body, "no route for any lens", TEXT_PX, RED);
			return;
		}

		addLine(body, xpText() + " xp · " + Fmt.hours(p.hours == null ? 0 : p.hours)
			+ " <font color='" + hex(DIM) + "'>("
			+ Fmt.hours(p.attentionHours == null ? 0 : p.attentionHours)
			+ " attn)</font>", TEXT_PX, null);

		StringBuilder cost = new StringBuilder(Fmt.netGp(p.netGp == null ? 0 : p.netGp));
		if (p.gpHr != null && p.gpHr != 0)
		{
			// netGp's sign convention is negative-earns; gpHr's is positive-earns,
			// so flip it for Fmt.netGp and it reads "profit 34.5k/hr".
			cost.append(" · ").append(Fmt.netGp(-p.gpHr)).append("/hr");
		}
		if (p.buyNowGp != null && p.buyNowGp > 0)
		{
			cost.append(" · buy now ").append(Fmt.gp(p.buyNowGp));
		}
		if (p.affordability != null && !"ok".equals(p.affordability))
		{
			cost.append(" <font color='").append(hex(AMBER)).append("'>")
				.append(esc(p.affordability)).append("</font>");
		}
		addLine(body, cost.toString(), TEXT_PX, DIM);

		if (step.incrementalFromXp != null)
		{
			addLine(body, "<i>continues from earlier training</i>", TEXT_PX, DIM, SMALL);
		}
		if (p.warnings != null)
		{
			for (String w : p.warnings)
			{
				addLine(body, esc(w), TEXT_PX, AMBER);
			}
		}
		if (p.toolGaps != null)
		{
			for (String t : p.toolGaps)
			{
				addLine(body, "tool: " + esc(t), TEXT_PX, AMBER);
			}
		}

		if (p.segments != null)
		{
			for (RecommendationsResponse.Segment s : p.segments)
			{
				body.add(Box.createVerticalStrut(3));
				body.add(segmentBlock(s));
			}
		}
		addBuys(p);
	}

	private String xpText()
	{
		return String.format("%,d", step.xpNeeded == null ? 0 : step.xpNeeded);
	}

	private void pickLens(String pick)
	{
		if (pick.equals(lens))
		{
			return;
		}
		lens = pick;
		onLensPick.accept(pick);
		rebuildBody();
	}

	/** One route band, deliberately terse: what, when to switch, how long, what it
	 * pays, where (once authored), and links. The authoring prose stays in the CLI. */
	private JPanel segmentBlock(RecommendationsResponse.Segment s)
	{
		JPanel block = roundedBlock(ColorScheme.DARK_GRAY_COLOR);

		addLine(block, esc(s.name) + " <font color='" + hex(DIM) + "'>"
			+ s.fromLevel + "→" + s.toLevel + "</font>", SUB_PX, null);

		StringBuilder line = new StringBuilder(Fmt.hours(s.hours));
		if (s.paced != null && !s.paced.isEmpty())
		{
			line.append(" <font color='").append(hex(AMBER)).append("'>(paced)</font>");
		}
		line.append(" · ").append(Fmt.netGp(s.netGp == null ? 0 : s.netGp));
		if (s.actions > 0)
		{
			line.append(" · ").append(String.format("%,d", s.actions)).append(" actions");
		}
		addLine(block, line.toString(), SUB_PX, DIM);

		if (s.inputs != null && !s.inputs.isEmpty())
		{
			StringBuilder needs = new StringBuilder();
			for (RecommendationsResponse.SegInput in : s.inputs)
			{
				if (needs.length() > 0)
				{
					needs.append(" · ");
				}
				needs.append(String.format("%,d", in.need)).append(" × ").append(esc(in.item));
			}
			addLine(block, needs.toString(), SUB_PX, DIM);
		}

		if (s.location != null && !s.location.isEmpty())
		{
			String where = "where: " + esc(s.location)
				+ (s.travel != null && !s.travel.isEmpty() ? " — " + esc(s.travel) : "");
			addLine(block, where, SUB_PX, DIM);
		}

		JPanel links = new JPanel();
		links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
		links.setOpaque(false);
		links.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (s.wiki != null && !s.wiki.isEmpty())
		{
			links.add(link("wiki ↗", WIKI_BASE + s.wiki.replace(' ', '_')));
		}
		if (s.video != null && !s.video.isEmpty())
		{
			if (links.getComponentCount() > 0)
			{
				links.add(Box.createHorizontalStrut(8));
			}
			links.add(link("video ▶", s.video));
		}
		if (links.getComponentCount() > 0)
		{
			block.add(links);
		}
		return block;
	}

	private JLabel link(String label, String url)
	{
		JLabel link = new JLabel("<html><u>" + label + "</u></html>");
		link.setFont(BODY);
		link.setForeground(DIM);
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setToolTipText(url);
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(url);
			}
		});
		return link;
	}

	/** "N buys" toggle revealing the aggregated shopping list for the current lens. */
	private void addBuys(RecommendationsResponse.Plan p)
	{
		List<RecommendationsResponse.Buy> buys = p.buys;
		if (buys == null || buys.isEmpty())
		{
			return;
		}

		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.setVisible(false);
		for (RecommendationsResponse.Buy b : buys)
		{
			addLine(detail, esc(b.item) + " × " + String.format("%,d", b.qty),
				INDENT_PX, DIM);
		}

		String caption = buys.size() + (buys.size() == 1 ? " buy" : " buys");
		JLabel toggle = new JLabel("▸ " + caption);
		toggle.setFont(BODY);
		toggle.setForeground(DIM);
		toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean show = !detail.isVisible();
				detail.setVisible(show);
				toggle.setText((show ? "▾ " : "▸ ") + caption);
				TrainCardPanel.this.revalidate();
				TrainCardPanel.this.repaint();
			}
		});
		body.add(Box.createVerticalStrut(3));
		body.add(toggle);
		body.add(detail);
	}

}

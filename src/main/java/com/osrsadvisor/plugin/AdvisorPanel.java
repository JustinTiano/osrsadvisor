package com.osrsadvisor.plugin;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * The sidebar: renders /api/recommendations as a scrollable list of rounded cards -
 * training steps and quest steps interleaved, in the order the server emits them. Lens
 * and step-count are session state, not config - the durable default lives server-side
 * in _meta.json, and a config-side copy would be a second source of truth.
 *
 * The global lens combo re-renders from the cached response rather than re-fetching:
 * every training step carries all three lens plans, so a lens change is answered
 * locally. Per-card pill overrides live in {@link #cardLens}, keyed by the train step's
 * stable id so they survive the full card rebuild every render does.
 *
 * Shopping mode replaces the card list with the response's merged buy list. It follows
 * the GLOBAL lens only - per-card pill overrides are deliberately ignored, or the
 * totals would disagree with no way to say which card they disagree about. The view is
 * reachable two ways: the cart toggle in the header, and automatically while the Grand
 * Exchange interface is open (the plugin's widget events call in); the manual toggle
 * survives GE close, the automatic one does not.
 *
 * Refresh triggers are deliberate and finite (button, open-when-stale, a successful
 * ingest push, a step-count change, a tick) - no polling timer, because every fetch can
 * cost the server a wiki price pull.
 *
 * Threading: this class mutates Swing only on the EDT; API callbacks arrive on OkHttp
 * threads and marshal in. {@link #refresh()} is safe from any thread and coalesces
 * concurrent calls into one in-flight fetch plus at most one queued follow-up.
 */
class AdvisorPanel extends PluginPanel
{
	/** Runs the continuation with the logged-in player's name, or null - client thread rules. */
	interface PlayerNameBridge
	{
		void withName(Consumer<String> next);
	}

	private static final long STALE_MS = 60_000;

	/** Goal-box text widths: half the sidebar, and the full row. */
	private static final int GOAL_PAIR_PX = 58;
	private static final int GOAL_WIDE_PX = 146;

	private final AdvisorApiClient api;
	private final PlayerNameBridge names;
	private final SkillIconManager skillIcons;
	private final SpriteManager sprites;

	// Card-title flair from the game's own sprite cache, fetched once and cached;
	// EDT only after the invokeLater hop. Cards render text-only until they land.
	private BufferedImage questIcon;
	private BufferedImage diaryIcon;
	private BufferedImage trainIcon;

	private final JLabel bankAlert = new JLabel();
	private final JPanel listPanel = new JPanel();
	private final JPanel goalsPanel = new JPanel();
	private final JLabel statusText = new JLabel();
	private final JButton undoButton = new JButton("Undo");
	private final JPanel statusBar = new JPanel(new BorderLayout(4, 0));
	private final JComboBox<String> lensBox =
		new JComboBox<>(new String[]{"Default", "Fastest", "Balanced", "AFK"});
	private final JComboBox<String> stepsBox = new JComboBox<>(stepChoices());

	// Read by refresh() from any thread; written only by EDT listeners.
	private volatile String lensOverride = null;
	private volatile int steps = 5;

	private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
	private volatile boolean pendingRefresh = false;
	private volatile boolean active = false;
	private volatile long lastFetchedAt = 0;

	// EDT only.
	// Shopping mode: manual is the header toggle, auto tracks the GE interface
	// being open. Either shows the buy list; an explicit toggle-off clears both.
	private boolean shoppingManual = false;
	private boolean shoppingAuto = false;
	private final JComponent shopToggle = new CartGlyph();
	private final Set<String> expandedKeys = new HashSet<>();
	private final Map<String, String> cardLens = new HashMap<>();
	private final Map<String, Integer> clientXp = new HashMap<>();
	private final Map<String, Integer> clientLevel = new HashMap<>();
	private final Map<String, GoalBoxPanel> goalBoxes = new HashMap<>();
	private RecommendationsResponse lastRec = null;
	private boolean seededExpansion = false;
	private Integer undoPos = null;

	AdvisorPanel(AdvisorApiClient api, PlayerNameBridge names,
		SkillIconManager skillIcons, SpriteManager sprites)
	{
		super(false);
		this.api = api;
		this.names = names;
		this.skillIcons = skillIcons;
		this.sprites = sprites;
		requestSprite(SpriteID.QUESTS_PAGE_ICON_BLUE_QUESTS, img -> questIcon = img);
		requestSprite(SpriteID.QUESTS_PAGE_ICON_GREEN_ACHIEVEMENT_DIARIES,
			img -> diaryIcon = img);
		requestSprite(SpriteID.QUESTS_PAGE_ICON_ORANGE_ADVENTURE_PATHS,
			img -> trainIcon = img);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		add(buildHeader(), BorderLayout.NORTH);

		listPanel.setLayout(new DynamicGridLayout(0, 1, 0, 4));
		listPanel.setOpaque(false);
		JPanel anchor = new JPanel(new BorderLayout());
		anchor.setOpaque(false);
		anchor.add(listPanel, BorderLayout.NORTH);
		// No scrollbar: its gutter was clipping the cards' right edge, and the
		// wheel scrolls the viewport regardless.
		JScrollPane scroll = new JScrollPane(anchor,
			JScrollPane.VERTICAL_SCROLLBAR_NEVER,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getViewport().setOpaque(false);
		scroll.setOpaque(false);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Swing's built-in wheel handling ignores a never-shown scrollbar, so the
		// wheel drives the (invisible) bar's model by hand.
		scroll.addMouseWheelListener(e ->
		{
			JScrollBar v = scroll.getVerticalScrollBar();
			v.setValue(v.getValue() + e.getUnitsToScroll() * v.getUnitIncrement());
		});
		add(scroll, BorderLayout.CENTER);

		statusText.setFont(CardPanel.BODY);
		statusText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		undoButton.setFont(CardPanel.BODY);
		undoButton.addActionListener(e -> undo());
		statusBar.setOpaque(false);
		statusBar.add(statusText, BorderLayout.CENTER);
		statusBar.add(undoButton, BorderLayout.EAST);
		statusBar.setVisible(false);

		// Goal boxes ride under the list, above the status bar - always in view,
		// never scrolled away with the cards.
		goalsPanel.setLayout(new DynamicGridLayout(0, 1, 0, 4));
		goalsPanel.setOpaque(false);
		goalsPanel.setVisible(false);
		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		south.add(goalsPanel, BorderLayout.NORTH);
		south.add(statusBar, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		showState("Loading…", null, null);
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setOpaque(false);
		header.setLayout(new DynamicGridLayout(0, 1, 0, 4));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setOpaque(false);
		JLabel title = new JLabel("OSRS Advisor");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		try
		{
			title.setIcon(new ImageIcon(ImageUtil.resizeImage(
				ImageUtil.loadImageResource(AdvisorPanel.class, "icon.png"), 18, 18)));
			title.setIconTextGap(6);
		}
		catch (RuntimeException e)
		{
			// Missing art never blocks the panel.
		}
		titleRow.add(title, BorderLayout.CENTER);

		JLabel refresh = new JLabel("⟳");
		refresh.setFont(FontManager.getDefaultBoldFont().deriveFont(16f));
		refresh.setForeground(CardPanel.DIM);
		refresh.setToolTipText("Refresh");
		refresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		refresh.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				refresh();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				refresh.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				refresh.setForeground(CardPanel.DIM);
			}
		});
		shopToggle.setForeground(CardPanel.DIM);
		shopToggle.setToolTipText("Shopping list — what to buy for the shown steps");
		shopToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		shopToggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleShopping();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!shoppingActive())
				{
					shopToggle.setForeground(Color.WHITE);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				restyleShopToggle();
			}
		});

		JPanel east = new JPanel();
		east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
		east.setOpaque(false);
		shopToggle.setAlignmentY(CENTER_ALIGNMENT);
		refresh.setAlignmentY(CENTER_ALIGNMENT);
		east.add(shopToggle);
		east.add(Box.createHorizontalStrut(8));
		east.add(refresh);
		titleRow.add(east, BorderLayout.EAST);
		header.add(titleRow);

		JPanel controls = new JPanel(new DynamicGridLayout(1, 2, 4, 0));
		controls.setOpaque(false);
		styleCombo(lensBox);
		lensBox.addActionListener(e ->
		{
			String pick = (String) lensBox.getSelectedItem();
			lensOverride = pick == null || "Default".equals(pick)
				? null : pick.toLowerCase();
			// All three lens plans are already in hand - re-render, don't re-fetch.
			rerender();
		});
		controls.add(lensBox);
		styleCombo(stepsBox);
		stepsBox.setSelectedIndex(steps - 1);   // before the listener - no spurious fetch
		stepsBox.addActionListener(e ->
		{
			int pick = stepsBox.getSelectedIndex() + 1;
			if (pick != steps)
			{
				steps = pick;
				refresh();
			}
		});
		controls.add(stepsBox);
		header.add(controls);

		bankAlert.setFont(CardPanel.BODY);
		bankAlert.setForeground(CardPanel.AMBER);
		bankAlert.setVisible(false);
		header.add(bankAlert);
		return header;
	}

	/** The flat dark look RuneLite's own plugins use for dropdowns. */
	private static void styleCombo(JComboBox<String> box)
	{
		box.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
				int index, boolean isSelected, boolean cellHasFocus)
			{
				Component c = super.getListCellRendererComponent(list, value, index,
					isSelected, cellHasFocus);
				setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
				setFont(CardPanel.BODY);
				return c;
			}
		});
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(Color.WHITE);
		box.setFont(CardPanel.BODY);
		box.setFocusable(false);
	}

	private static String[] stepChoices()
	{
		String[] out = new String[15];
		for (int i = 1; i <= 15; i++)
		{
			out[i - 1] = i + (i == 1 ? " step" : " steps");
		}
		return out;
	}

	// --- lifecycle hooks (plugin + PluginPanel) ---------------------------

	@Override
	public void onActivate()
	{
		active = true;
		if (lastFetchedAt == 0 || System.currentTimeMillis() - lastFetchedAt > STALE_MS)
		{
			refresh();
		}
	}

	@Override
	public void onDeactivate()
	{
		active = false;
	}

	/** A push just landed server-side, so the guide may have moved under us. */
	void noteIngestPushed()
	{
		if (active)
		{
			refresh();
		}
		else
		{
			lastFetchedAt = 0;   // next open refetches
		}
	}

	// --- fetching ---------------------------------------------------------

	/** Safe from any thread. Coalesces: one in flight, at most one queued behind it. */
	void refresh()
	{
		if (fetchInFlight.getAndSet(true))
		{
			pendingRefresh = true;
			return;
		}
		names.withName(name -> api.fetchRecommendations(name, steps, lensOverride,
			new AdvisorApiClient.ApiCallback<RecommendationsResponse>()
			{
				@Override
				public void onSuccess(RecommendationsResponse rec)
				{
					lastFetchedAt = System.currentTimeMillis();
					SwingUtilities.invokeLater(() -> render(rec));
					finishFetch();
				}

				@Override
				public void onError(AdvisorApiClient.ApiError error)
				{
					SwingUtilities.invokeLater(() -> renderError(error));
					finishFetch();
				}
			}));
	}

	private void finishFetch()
	{
		fetchInFlight.set(false);
		if (pendingRefresh)
		{
			pendingRefresh = false;
			refresh();
		}
	}

	// --- rendering (EDT) --------------------------------------------------

	private void render(RecommendationsResponse rec)
	{
		lastRec = rec;
		if (rec.needsSetup)
		{
			bankAlert.setVisible(false);
			renderGoals(null);
			showState("No account on record yet — log in once and the plugin's "
				+ "first push registers it.", null, null);
			return;
		}
		updateBankAlert(rec.holdings);
		renderGoals(rec.goals);

		if (shoppingActive())
		{
			renderShopping(rec);
			return;
		}

		listPanel.removeAll();
		if (rec.steps == null || rec.steps.isEmpty())
		{
			showState("All caught up — no unticked guide rows.", null, null);
			return;
		}
		if (!seededExpansion && expandedKeys.isEmpty())
		{
			expandedKeys.add(keyOf(rec.steps.get(0)));   // open the current step once
			seededExpansion = true;
		}
		for (RecommendationsResponse.Step step : rec.steps)
		{
			String key = keyOf(step);
			Runnable onToggle = () ->
			{
				if (!expandedKeys.remove(key))
				{
					expandedKeys.add(key);
				}
			};
			boolean open = expandedKeys.contains(key);
			if ("train".equals(step.kind))
			{
				String id = step.id;
				listPanel.add(new TrainCardPanel(step, open, effectiveLens(id),
					onToggle, pick -> noteCardLens(id, pick), trainIcon));
			}
			else if (step.gates != null || step.name != null)
			{
				// "quest", and any future kind that still looks like a guide row.
				listPanel.add(new QuestCardPanel(step, open, onToggle, this::markDone,
					step.diary ? diaryIcon : questIcon));
			}
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	/** Re-render the cached response - a lens change needs no new data. */
	private void rerender()
	{
		if (lastRec != null)
		{
			render(lastRec);
		}
	}

	// --- shopping mode (EDT) ----------------------------------------------

	private boolean shoppingActive()
	{
		return shoppingManual || shoppingAuto;
	}

	private void restyleShopToggle()
	{
		shopToggle.setForeground(shoppingActive() ? CardPanel.AMBER : CardPanel.DIM);
	}

	/** The header toggle: always flips the view. Off also clears the GE-open flag,
	 * so an explicit off wins even mid-GE; reopening the exchange re-arms it. */
	private void toggleShopping()
	{
		boolean on = !shoppingActive();
		shoppingManual = on;
		if (!on)
		{
			shoppingAuto = false;
		}
		restyleShopToggle();
		rerender();
	}

	/** The GE interface just opened - the plugin's widget event, EDT-marshalled.
	 * Only mutates flags: with no cached response yet, the current loading or
	 * error state stays put and the next successful render takes this branch. */
	void noteGeOpened()
	{
		shoppingAuto = true;
		restyleShopToggle();
		rerender();
		if (active && System.currentTimeMillis() - lastFetchedAt > STALE_MS)
		{
			refresh();
		}
	}

	/** GE closed: the automatic flag drops, a manual toggle-on survives. */
	void noteGeClosed()
	{
		shoppingAuto = false;
		restyleShopToggle();
		rerender();
	}

	/** Shopping follows the GLOBAL lens only - see the class comment. */
	private void renderShopping(RecommendationsResponse rec)
	{
		listPanel.removeAll();
		RecommendationsResponse.Shopping s = rec.shopping == null
			? null : rec.shopping.get(globalDefault());
		if (s == null)
		{
			showState("Nothing to buy for the next " + rec.stepsRequested
				+ (rec.stepsRequested == 1 ? " step." : " steps."), null, null);
			return;
		}
		listPanel.add(new ShoppingPanel(s));
		listPanel.revalidate();
		listPanel.repaint();
	}

	/** The header's shopping-cart glyph, drawn in the foreground color so the
	 * DIM / hover-white / active-amber restyling the text controls use works
	 * unchanged. Painted, not a font glyph or asset: emoji coverage in Swing
	 * fonts is unreliable and a PNG can't be recolored per state. */
	private static final class CartGlyph extends JComponent
	{
		CartGlyph()
		{
			Dimension size = new Dimension(16, 16);
			setPreferredSize(size);
			setMinimumSize(size);
			setMaximumSize(size);
		}

		@Override
		public void setForeground(Color fg)
		{
			super.setForeground(fg);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(getForeground());
			g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND));
			// Handle, then the basket trapezoid; wheels filled below.
			Path2D.Float cart = new Path2D.Float();
			cart.moveTo(1.5, 2.5);
			cart.lineTo(4.0, 2.5);
			cart.lineTo(5.9, 9.5);
			cart.lineTo(12.6, 9.5);
			cart.lineTo(14.2, 4.5);
			cart.lineTo(4.6, 4.5);
			g2.draw(cart);
			g2.fill(new Ellipse2D.Float(5.6f, 11.5f, 3f, 3f));
			g2.fill(new Ellipse2D.Float(10.6f, 11.5f, 3f, 3f));
			g2.dispose();
		}
	}

	private static String keyOf(RecommendationsResponse.Step step)
	{
		return "train".equals(step.kind) && step.id != null ? step.id : "q:" + step.pos;
	}

	/** The lens a training card should open on: its own override, else the global pick,
	 * else the server-resolved default that came with the response. */
	private String effectiveLens(String id)
	{
		String override = cardLens.get(id);
		if (override != null)
		{
			return override;
		}
		return globalDefault();
	}

	private String globalDefault()
	{
		String pick = (String) lensBox.getSelectedItem();
		if (pick != null && !"Default".equals(pick))
		{
			return pick;
		}
		return lastRec != null && lastRec.lens != null ? lastRec.lens : "Balanced";
	}

	/** A pill matching the global default clears the override - the card follows again. */
	private void noteCardLens(String id, String pick)
	{
		if (pick.equals(globalDefault()))
		{
			cardLens.remove(id);
		}
		else
		{
			cardLens.put(id, pick);
		}
	}

	private void renderError(AdvisorApiClient.ApiError error)
	{
		switch (error.kind)
		{
			case UNREACHABLE:
				showState("Companion server not reachable.<br>"
					+ "This plugin plans against your own OSRS Advisor server - run "
					+ "<b>python tools/osrs.py serve</b> and check the Ingest URL in the "
					+ "plugin settings. Setup guide:<br>"
					+ "<b>github.com/JustinTiano/osrsadvisor</b>", "Retry", this::refresh);
				break;
			case HTTP:
				showState("Server error " + error.code + ": "
					+ CardPanel.esc(error.message), "Retry", this::refresh);
				break;
			default:
				showState("Unreadable response: " + CardPanel.esc(error.message),
					"Retry", this::refresh);
		}
	}

	private void showState(String html, String buttonText, Runnable action)
	{
		listPanel.removeAll();
		JLabel label = new JLabel("<html><body style='width:" + CardPanel.TEXT_PX
			+ "px'>" + html + "</body></html>");
		label.setFont(CardPanel.BODY);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 2));
		listPanel.add(label);
		if (buttonText != null)
		{
			JButton button = new JButton(buttonText);
			button.setFont(CardPanel.BODY);
			button.addActionListener(e -> action.run());
			listPanel.add(button);
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	/** One game-cache sprite, scaled for a card title; re-renders when it lands. */
	private void requestSprite(int spriteId, Consumer<BufferedImage> store)
	{
		sprites.getSpriteAsync(spriteId, 0, img ->
			SwingUtilities.invokeLater(() ->
			{
				store.accept(ImageUtil.resizeImage(img, 16, 16));
				rerender();
			}));
	}

	// --- goal boxes (EDT) -------------------------------------------------

	/** Rebuilds the three goal boxes: Slayer + Combat side by side, Priority AFK
	 * full-width beneath. Absent goals hide their box; all absent hides the strip. */
	private void renderGoals(RecommendationsResponse.Goals g)
	{
		goalsPanel.removeAll();
		goalBoxes.clear();
		boolean any = g != null && (g.slayer != null || g.combat != null || g.afk != null);
		if (any)
		{
			goalsPanel.add(divider());
			if (g.slayer != null && g.combat != null)
			{
				JPanel pair = new JPanel(new GridLayout(1, 2, 4, 0));
				pair.setOpaque(false);
				pair.add(goalBox(g.slayer, null, GOAL_PAIR_PX, true));
				pair.add(goalBox(g.combat, null, GOAL_PAIR_PX, true));
				goalsPanel.add(pair);
			}
			else if (g.slayer != null)
			{
				goalsPanel.add(goalBox(g.slayer, null, GOAL_WIDE_PX, true));
			}
			else if (g.combat != null)
			{
				goalsPanel.add(goalBox(g.combat, null, GOAL_WIDE_PX, true));
			}
			if (g.afk != null)
			{
				goalsPanel.add(goalBox(g.afk,
					"Priority AFK — " + g.afk.skill, GOAL_WIDE_PX, false));
			}
		}
		goalsPanel.setVisible(any);
		goalsPanel.revalidate();
		goalsPanel.repaint();
	}

	/** A thin inset line: clear separation between the guide list and the goals. */
	private static JPanel divider()
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
		JPanel line = new JPanel();
		line.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		line.setPreferredSize(new Dimension(0, 1));
		wrap.add(line, BorderLayout.CENTER);
		return wrap;
	}

	private GoalBoxPanel goalBox(RecommendationsResponse.Goal goal, String header,
		int textPx, boolean levelOnly)
	{
		// The totem boxes carry the blown-up icon; the AFK box keeps the small one.
		GoalBoxPanel box = new GoalBoxPanel(goal, skillIcon(goal.skill, !levelOnly),
			header, textPx, levelOnly);
		goalBoxes.put(goal.skill, box);
		// The client can already be ahead of the response we just fetched.
		Integer xp = clientXp.get(goal.skill);
		Integer lvl = clientLevel.get(goal.skill);
		if (xp != null || lvl != null)
		{
			box.updateStat(xp == null ? 0 : xp, lvl == null ? 0 : lvl);
		}
		return box;
	}

	/** null when the server names a skill this client build doesn't have - the box
	 * degrades to text-only rather than throwing. */
	private BufferedImage skillIcon(String name, boolean small)
	{
		if (name == null || skillIcons == null)
		{
			return null;
		}
		try
		{
			return skillIcons.getSkillImage(
				Skill.valueOf(name.toUpperCase(Locale.ROOT)), small);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** Client-side xp and level, forwarded by the plugin on every xp drop. EDT only. */
	void noteStat(String skill, int xp, int level)
	{
		Integer prevXp = clientXp.get(skill);
		Integer prevLvl = clientLevel.get(skill);
		boolean moved = prevXp == null || xp > prevXp
			|| prevLvl == null || level > prevLvl;
		if (!moved)
		{
			return;
		}
		clientXp.merge(skill, xp, Math::max);
		clientLevel.merge(skill, level, Math::max);
		GoalBoxPanel box = goalBoxes.get(skill);
		if (box != null)
		{
			box.updateStat(clientXp.get(skill), clientLevel.get(skill));
		}
	}

	/** The full stat map at login, so the boxes tick from truth before any xp drop. */
	void seedStats(Map<String, Integer> xp, Map<String, Integer> levels)
	{
		for (Map.Entry<String, Integer> e : xp.entrySet())
		{
			Integer lvl = levels.get(e.getKey());
			noteStat(e.getKey(), e.getValue(), lvl == null ? 0 : lvl);
		}
	}

	/** Another account's stats are not this account's overlay. */
	void clearClientXp()
	{
		clientXp.clear();
		clientLevel.clear();
	}

	private static final long BANK_STALE_S = 24 * 3600;

	/** The one piece of provenance worth a banner: a holdings snapshot the advisor is
	 * costing against that hasn't seen a bank in a day. Fresh data shows nothing. */
	private void updateBankAlert(RecommendationsResponse.Holdings h)
	{
		Long age = h == null ? null
			: isoAgeSeconds(h.bankAt != null ? h.bankAt : h.at);
		String text;
		if (age == null)
		{
			text = "No bank snapshot yet — open a bank with the plugin running.";
		}
		else if (age > BANK_STALE_S)
		{
			text = "Visit a bank — snapshot " + Fmt.age(age) + ".";
		}
		else
		{
			bankAlert.setVisible(false);
			return;
		}
		bankAlert.setText("<html><body style='width:" + CardPanel.TEXT_PX + "px'>"
			+ text + "</body></html>");
		bankAlert.setVisible(true);
	}

	/** holdings timestamps are naive local-time isoformat() - age best-effort, never fail. */
	private static Long isoAgeSeconds(String iso)
	{
		if (iso == null)
		{
			return null;
		}
		try
		{
			LocalDateTime then = LocalDateTime.parse(iso.length() > 19
				? iso.substring(0, 19) : iso);
			long s = Duration.between(then, LocalDateTime.now()).getSeconds();
			return s >= 0 ? s : null;
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	// --- status bar (EDT) -------------------------------------------------

	/** Status-bar text, offering Undo for the given guide row or nothing at all. */
	private void showStatus(String text, Integer undoSlot)
	{
		undoPos = undoSlot;
		statusText.setText(text);
		undoButton.setVisible(undoSlot != null);
		statusBar.setVisible(true);
	}

	private void showStatus(String text)
	{
		showStatus(text, null);
	}

	// --- ticking ----------------------------------------------------------

	private void markDone(RecommendationsResponse.Step step)
	{
		toggle(step.pos, true,
			"Ticked #" + step.pos + " " + step.name, step.pos);
	}

	private void undo()
	{
		Integer pos = undoPos;
		if (pos != null)
		{
			toggle(pos, false, "Restored #" + pos, null);
		}
	}

	private void toggle(int pos, boolean done, String successText, Integer undoSlot)
	{
		names.withName(name -> api.toggle(name, pos, done,
			new AdvisorApiClient.ApiCallback<Boolean>()
			{
				@Override
				public void onSuccess(Boolean ok)
				{
					SwingUtilities.invokeLater(() -> showStatus(successText, undoSlot));
					refresh();   // the guide position just moved
				}

				@Override
				public void onError(AdvisorApiClient.ApiError error)
				{
					SwingUtilities.invokeLater(() ->
						showStatus("Tick failed: " + error.message));
				}
			}));
	}
}

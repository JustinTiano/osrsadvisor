package com.osrsadvisor.plugin;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Pushes account state to the local planning workspace.
 *
 * One direction only: this plugin reads the client and POSTs, it never acts on the game.
 *
 * Sends skill <em>xp</em>, not levels, and the distinction is the whole point. The workspace
 * treats a level written to disk as a correctness bug, because a stale copy silently corrupts
 * every cost estimate downstream. It used to answer that by never sending skills at all and
 * always reading the hiscores - but the hiscores only refresh on logout, so every level was
 * stale for exactly the session being planned around.
 *
 * xp resolves that. It only ever goes up, so the server takes max(hiscores, push) per skill:
 * never worse than either source, and staleness corrects itself in both directions. The result
 * lives in memory for the life of the server process and nowhere else - the advisor server strips
 * skills from every payload on the way to disk, and that strip, not this comment, is the
 * guarantee.
 *
 * Quest and achievement-diary completion are discrete and absent from the hiscores entirely,
 * which is why they are worth pushing at all.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Advisor",
	description = "Quest and training recommendations from your own OSRS Advisor companion "
		+ "server. Sends your player name, skill xp, quest and diary completion, bank, "
		+ "inventory, equipment and GE offers to the server URL you configure (your own "
		+ "machine by default).",
	tags = {"quest", "diary", "bank", "advisor", "planner", "export"}
)
public class AdvisorPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private AdvisorConfig config;
	@Inject private ItemManager itemManager;
	@Inject private SkillIconManager skillIconManager;
	@Inject private SpriteManager spriteManager;
	@Inject private OkHttpClient http;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;
	@Inject private ClientToolbar clientToolbar;

	private final AtomicBoolean dirty = new AtomicBoolean(false);
	private volatile long lastPush = 0L;
	private ScheduledFuture<?> ticker;
	private AdvisorPanel panel;
	private NavigationButton navButton;

	/**
	 * The bank container only exists while the bank is open. Pushes are debounced, so by the
	 * time one runs the bank is usually closed again and reads back empty - which is why the
	 * bank landed on the server only when a push happened to coincide with it being open.
	 * Capture it the moment it is seen instead, and stamp when that was.
	 */
	private volatile List<List<Object>> lastBank = null;
	private volatile long lastBankAt = 0L;

	/**
	 * Last real level seen per skill, so onStatChanged can tell a level-up from any other
	 * xp drop. Only ever touched on the client thread.
	 */
	private final Map<Skill, Integer> lastLevel = new EnumMap<>(Skill.class);

	@Provides
	AdvisorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdvisorConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastPush = 0L;
		lastLevel.clear();
		ticker = executor.scheduleWithFixedDelay(this::tick, 10, 5, TimeUnit.SECONDS);

		panel = new AdvisorPanel(new AdvisorApiClient(http, gson, config),
			this::withPlayerName, skillIconManager, spriteManager);
		navButton = NavigationButton.builder()
			.tooltip("OSRS Advisor")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		log.info("Advisor push enabled -> {}", config.url());
	}

	@Override
	protected void shutDown()
	{
		if (ticker != null)
		{
			ticker.cancel(false);
			ticker = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	/**
	 * Runs the continuation with the logged-in player's name, or null when it isn't
	 * knowable. Reading the local player belongs on the client thread, same rule as the
	 * varbit reads; the continuation only enqueues an async HTTP call, so staying on the
	 * client thread is safe. The name is sent raw, exactly as collectAndSend() sends it,
	 * so the server joins both on the identical string.
	 */
	private void withPlayerName(Consumer<String> next)
	{
		clientThread.invokeLater(() ->
		{
			String name = client.getGameState() == GameState.LOGGED_IN
				&& client.getLocalPlayer() != null
				? client.getLocalPlayer().getName() : null;
			next.accept(name);
		});
	}

	/** Toolbar icon: the shipped artwork (64px resource), scaled once at startup. */
	private static BufferedImage icon()
	{
		return ImageUtil.resizeImage(
			ImageUtil.loadImageResource(AdvisorPlugin.class, "icon.png"), 16, 16);
	}

	// --- what makes a push worth sending ---------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		// Varbits aren't populated the instant the state flips; the debounce covers the gap.
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			dirty.set(true);
			// Seed the goal boxes from the client's own stats - already on the client
			// thread, and the panel's merge-max absorbs any still-zero login reads.
			AdvisorPanel p = panel;
			if (p != null)
			{
				Map<String, Integer> xp = skills();
				Map<String, Integer> levels = new HashMap<>();
				for (Skill s : Skill.values())
				{
					try
					{
						levels.put(s.getName(), client.getRealSkillLevel(s));
					}
					catch (Exception ex)
					{
						// A skill this client build doesn't know about - skip it.
					}
				}
				SwingUtilities.invokeLater(() -> p.seedStats(xp, levels));
			}
		}
		else
		{
			// Another account's levels are not this account's baseline.
			lastLevel.clear();
			if (e.getGameState() == GameState.LOGIN_SCREEN)
			{
				// Account switches pass through here. LOADING/HOPPING deliberately
				// don't clear - a region crossing would visibly revert the boxes.
				AdvisorPanel p = panel;
				if (p != null)
				{
					SwingUtilities.invokeLater(p::clearClientXp);
				}
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// StatChanged fires on every xp drop, which is far too often to push on. A level-up
		// is the moment the plan actually changes - a gate closes - so that is the trigger.
		// The xp itself rides along on the next push, whatever causes it.
		Integer prev = lastLevel.put(e.getSkill(), e.getLevel());
		if (prev != null && e.getLevel() > prev)
		{
			log.debug("{} reached {} - pushing", e.getSkill().getName(), e.getLevel());
			dirty.set(true);
		}

		// The goal boxes tick on every drop, locally - no push, no fetch.
		AdvisorPanel p = panel;
		if (p != null)
		{
			String skillName = e.getSkill().getName();
			int xp = e.getXp();
			int level = e.getLevel();
			SwingUtilities.invokeLater(() -> p.noteStat(skillName, xp, level));
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		if (id == InventoryID.BANK)
		{
			// Already on the client thread with the container populated - read it now.
			List<List<Object>> snapshot = container(InventoryID.BANK);
			if (!snapshot.isEmpty())
			{
				lastBank = snapshot;
				lastBankAt = System.currentTimeMillis();
			}
			dirty.set(true);
		}
		else if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			dirty.set(true);
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		dirty.set(true);
	}

	// The panel's shopping mode tracks the GE interface itself - offer changes above
	// say what's trading, these say the exchange is on screen at all.

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.GE_OFFERS)
		{
			AdvisorPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(p::noteGeOpened);
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.GE_OFFERS)
		{
			AdvisorPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(p::noteGeClosed);
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		// Quest points tick over at the exact moment a quest completes, which is the one
		// event worth pushing for. Without this, finishing a quest waited on the heartbeat.
		if (e.getVarpId() == VarPlayerID.QP)
		{
			log.debug("quest points changed to {} - pushing", e.getValue());
			dirty.set(true);
			return;
		}
		// The same moment for a diary, which awards no quest points. A fast path, not the
		// guarantee: getVarbitId() only names varbits the client has registered, and reading
		// them in diaries() is what registers these - so the first push of a session is what
		// arms it. Everything else (a level, an inventory change, the heartbeat) still lands
		// the completion within a push or two regardless.
		if (DIARY_VARBIT_IDS.contains(e.getVarbitId()))
		{
			log.debug("diary varbit {} changed to {} - pushing", e.getVarbitId(), e.getValue());
			dirty.set(true);
		}
	}

	private void tick()
	{
		try
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			long now = System.currentTimeMillis();
			long sinceLast = now - lastPush;
			boolean debounced = sinceLast >= Math.max(5, config.debounceSeconds()) * 1000L;
			boolean heartbeat = config.intervalMinutes() > 0
				&& sinceLast >= config.intervalMinutes() * 60_000L;

			if ((dirty.get() && debounced) || heartbeat)
			{
				dirty.set(false);
				lastPush = now;
				// Reading varbits and containers is only legal on the client thread.
				clientThread.invoke(this::collectAndSend);
			}
		}
		catch (Exception ex)
		{
			log.warn("advisor tick failed", ex);
		}
	}

	// --- collection (client thread) --------------------------------------

	private void collectAndSend()
	{
		try
		{
			Map<String, Object> payload = new HashMap<>();
			payload.put("at", System.currentTimeMillis());
			payload.put("player", client.getLocalPlayer() != null
				? client.getLocalPlayer().getName() : null);
			payload.put("quests", quests());
			payload.put("diaries", diaries());
			// The account's real QP, not a sum derived from ticked rows. If the two
			// disagree, the guide is missing ticks - which is worth knowing, not hiding.
			payload.put("questPoints", client.getVarpValue(VarPlayerID.QP));

			if (config.sendBank())
			{
				// Prefer a live read; fall back to the last one seen this session. bankAt says
				// which it was, so nothing downstream has to guess how current the bank is.
				List<List<Object>> live = container(InventoryID.BANK);
				if (!live.isEmpty())
				{
					lastBank = live;
					lastBankAt = System.currentTimeMillis();
				}
				if (lastBank != null)
				{
					payload.put("bank", lastBank);
					payload.put("bankAt", lastBankAt);
				}
			}
			if (config.sendCarried())
			{
				payload.put("inventory", container(InventoryID.INV));
				payload.put("equipment", container(InventoryID.WORN));
			}
			if (config.sendGeOffers())
			{
				payload.put("geOffers", geOffers());
			}
			if (config.sendSkills())
			{
				payload.put("skills", skills());
			}

			String body = gson.toJson(payload);
			executor.execute(() -> send(body));
		}
		catch (Exception ex)
		{
			log.warn("advisor collection failed", ex);
		}
	}

	/**
	 * {skill name -> xp}. xp rather than level, because the server takes max(hiscores, push)
	 * and only xp is monotonic enough for that to be safe. OVERALL is skipped where the
	 * client exposes it: it is a total, so deriving a level from it is meaningless.
	 */
	private Map<String, Integer> skills()
	{
		Map<String, Integer> out = new HashMap<>();
		for (Skill skill : Skill.values())
		{
			String name = skill.getName();
			if ("Overall".equalsIgnoreCase(name))
			{
				continue;
			}
			try
			{
				out.put(name, client.getSkillExperience(skill));
			}
			catch (Exception ex)
			{
				// A skill this client build doesn't know about. Skipping one is survivable;
				// failing the whole push over it is not.
			}
		}
		return out;
	}

	private List<Map<String, String>> quests()
	{
		List<Map<String, String>> out = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			QuestState state;
			try
			{
				state = q.getState(client);
			}
			catch (Exception ex)
			{
				continue;   // a quest the current cache doesn't know about
			}
			Map<String, String> row = new HashMap<>();
			row.put("name", q.getName());
			row.put("state", state.name());
			out.add(row);
		}
		return out;
	}

	/**
	 * Achievement diary completion varbits, keyed by the name the workspace's guide gives the
	 * row: "&lt;Region&gt; Diary#&lt;Tier&gt;", which is exactly the form quest_key() folds onto
	 * the canonical ids in plans/quest-ids.tsv.
	 *
	 * Diaries need their own read because they are <em>not</em> quests: RuneLite's Quest enum
	 * contains quests only, so a finished diary was absent from every push, the server had no
	 * name to join on, and the row could never be auto-ticked - silently, since a name that is
	 * never sent cannot turn up as 'unmatched' either.
	 *
	 * The region labels are load-bearing, not cosmetic. "Kourend &amp; Kebos", "Lumbridge &amp;
	 * Draynor" and "Western Provinces" are what the guide calls those rows; shortening any of
	 * them breaks the join back to a silent no-tick. AdvisorDiaryJoinTest pins them.
	 */
	private static final Map<String, Integer> DIARY_VARBITS = diaryVarbits();

	private static final Set<Integer> DIARY_VARBIT_IDS = new HashSet<>(DIARY_VARBITS.values());

	private static Map<String, Integer> diaryVarbits()
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		tiers(m, "Ardougne", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE);
		tiers(m, "Desert", VarbitID.DESERT_DIARY_EASY_COMPLETE,
			VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE,
			VarbitID.DESERT_DIARY_ELITE_COMPLETE);
		tiers(m, "Falador", VarbitID.FALADOR_DIARY_EASY_COMPLETE,
			VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE,
			VarbitID.FALADOR_DIARY_ELITE_COMPLETE);
		tiers(m, "Fremennik", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE,
			VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE,
			VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE);
		tiers(m, "Kandarin", VarbitID.KANDARIN_DIARY_EASY_COMPLETE,
			VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE,
			VarbitID.KANDARIN_DIARY_ELITE_COMPLETE);
		// Karamja is the odd one out: the original diary, and the only one whose easy, medium
		// and hard flags predate the DIARY_*_COMPLETE naming. "ATJUN" is its internal name.
		tiers(m, "Karamja", VarbitID.ATJUN_EASY_DONE,
			VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE,
			VarbitID.KARAMJA_DIARY_ELITE_COMPLETE);
		tiers(m, "Kourend & Kebos", VarbitID.KOUREND_DIARY_EASY_COMPLETE,
			VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE,
			VarbitID.KOUREND_DIARY_ELITE_COMPLETE);
		tiers(m, "Lumbridge & Draynor", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE);
		tiers(m, "Morytania", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE,
			VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE,
			VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE);
		tiers(m, "Varrock", VarbitID.VARROCK_DIARY_EASY_COMPLETE,
			VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE,
			VarbitID.VARROCK_DIARY_ELITE_COMPLETE);
		tiers(m, "Western Provinces", VarbitID.WESTERN_DIARY_EASY_COMPLETE,
			VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE,
			VarbitID.WESTERN_DIARY_ELITE_COMPLETE);
		tiers(m, "Wilderness", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE,
			VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE,
			VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE);
		return m;
	}

	private static void tiers(Map<String, Integer> m, String region,
		int easy, int medium, int hard, int elite)
	{
		m.put(region + " Diary#Easy", easy);
		m.put(region + " Diary#Medium", medium);
		m.put(region + " Diary#Hard", hard);
		m.put(region + " Diary#Elite", elite);
	}

	/**
	 * Same shape as quests(), so the server joins both lists through one code path. The state
	 * is only ever FINISHED or INCOMPLETE - a completion varbit cannot tell "not started" from
	 * "nine of ten tasks done", and claiming it could would be inventing progress.
	 */
	private List<Map<String, String>> diaries()
	{
		List<Map<String, String>> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : DIARY_VARBITS.entrySet())
		{
			int value;
			try
			{
				value = client.getVarbitValue(e.getValue());
			}
			catch (Exception ex)
			{
				continue;   // a varbit this client build doesn't know about
			}
			Map<String, String> row = new HashMap<>();
			row.put("name", e.getKey());
			// Karamja's easy/medium/hard varbits go to 2 once the reward is claimed, so
			// completion is "> 0". "== 1" would un-finish those three the moment you
			// collected the gloves.
			row.put("state", value > 0 ? "FINISHED" : "INCOMPLETE");
			out.add(row);
		}
		return out;
	}

	/**
	 * [[id, qty, name], ...] - the name comes from the client because the GE mapping the
	 * workspace caches only covers tradeable items, so quest items would otherwise be
	 * unnameable on the other end.
	 */
	private List<List<Object>> container(int containerId)
	{
		List<List<Object>> out = new ArrayList<>();
		ItemContainer c = client.getItemContainer(containerId);
		if (c == null)
		{
			return out;
		}
		Map<Integer, Integer> totals = new HashMap<>();
		for (Item item : c.getItems())
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;   // -1 is an empty slot
			}
			// A bank placeholder is its own item id at QUANTITY 1, wearing the real
			// item's name - only the composition betrays it. Unfiltered, one read as
			// "1 x Bread" and the advisor stopped saying to buy bread.
			try
			{
				if (itemManager.getItemComposition(item.getId())
					.getPlaceholderTemplateId() != -1)
				{
					continue;
				}
			}
			catch (Exception ex)
			{
				// Unknowable composition: keep the row rather than drop a real item.
			}
			totals.merge(item.getId(), item.getQuantity(), Integer::sum);
		}
		for (Map.Entry<Integer, Integer> e : totals.entrySet())
		{
			List<Object> row = new ArrayList<>(3);
			row.add(e.getKey());
			row.add(e.getValue());
			try
			{
				row.add(itemManager.getItemComposition(e.getKey()).getName());
			}
			catch (Exception ex)
			{
				row.add(null);
			}
			out.add(row);
		}
		return out;
	}

	private List<Map<String, Object>> geOffers()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return out;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer o = offers[slot];
			if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			Map<String, Object> row = new HashMap<>();
			row.put("slot", slot);
			row.put("item", o.getItemId());
			row.put("qty", o.getTotalQuantity());
			row.put("filled", o.getQuantitySold());
			row.put("price", o.getPrice());
			row.put("state", o.getState().name());
			row.put("buy", o.getState() == GrandExchangeOfferState.BUYING
				|| o.getState() == GrandExchangeOfferState.BOUGHT);
			out.add(row);
		}
		return out;
	}

	// --- transport (executor thread) -------------------------------------

	private void send(String body)
	{
		String url = config.url();
		if (url == null || url.isEmpty())
		{
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, body))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// The advisor server not running is the normal case, not an error worth shouting about.
				log.debug("advisor push failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("advisor push returned {}", r.code());
					}
					else if (panel != null)
					{
						// The server auto-ticks synchronously before responding, so
						// the guide the panel shows may just have moved.
						panel.noteIngestPushed();
					}
				}
			}
		});
	}
}

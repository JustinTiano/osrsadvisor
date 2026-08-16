package com.osrsadvisor.plugin;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Gson mirror of GET /api/recommendations (tools/advisor/advisor.py builds it).
 * Schema 2: steps interleave two kinds - "train" steps (one per short skill gate,
 * carrying all three lens plans so the panel can switch without a round trip) and
 * "quest" steps (the guide row, whose gates point back via train_ref). Step is the
 * union of both shapes; {@code kind} says which fields are live. Numbers that can be
 * absent are wrapper types; the no-account shape rides in the same class via
 * {@code needsSetup} so one parse handles both.
 */
public class RecommendationsResponse
{
	public String player;
	public String slug;
	public int schema;
	@SerializedName("generated_at") public long generatedAt;
	@SerializedName("steps_requested") public int stepsRequested;
	public String lens;
	public StatsMeta stats;
	public List<Step> steps;
	public Holdings holdings;
	public Wealth wealth;
	@SerializedName("hour_gp") public Double hourGp;
	@SerializedName("time_value_source") public String timeValueSource;
	/** The three goal boxes; each is omitted server-side when there is no goal. */
	public Goals goals;
	/**
	 * Lens name -> the merged buy list for the whole window (tools/advisor/shopping.py
	 * has the merge doctrine). Absent entirely when nothing needs buying.
	 */
	public Map<String, Shopping> shopping;

	@SerializedName("needs_setup") public boolean needsSetup;
	public String error;

	public static class StatsMeta
	{
		public boolean hiscores;
		public int live;
		@SerializedName("live_at") public Long liveAt;
		@SerializedName("live_age_s") public Integer liveAgeS;
		public List<String> ahead;
	}

	/** Union of the two step kinds - "quest" fields above the divider, "train" below. */
	public static class Step
	{
		public String kind;     // quest | train

		public int pos;
		public String name;
		public boolean diary;
		public Integer qp;
		public List<String> notes;
		public List<Gate> gates;
		public List<Prereq> prereqs;
		public List<ItemReq> items;
		@SerializedName("items_short") public int itemsShort;
		public boolean ready;

		public String id;       // "train:<Skill>:<forPos>" - stable across refreshes
		public String skill;
		@SerializedName("for_pos") public Integer forPos;
		@SerializedName("for_name") public String forName;
		public boolean boostable;
		public boolean midquest;
		@SerializedName("from_level") public Integer fromLevel;
		@SerializedName("to_level") public Integer toLevel;
		@SerializedName("from_xp") public Long fromXp;
		@SerializedName("to_xp") public Long toXp;
		@SerializedName("xp_needed") public Long xpNeeded;
		@SerializedName("incremental_from_xp") public Long incrementalFromXp;
		public String status;   // route | uncosted | error
		public String checked;
		/** Lens name -> plan; a lens whose chain did not converge is an explicit null. */
		public Map<String, Plan> plans;
		public String note;     // status=uncosted
		public String error;    // status=error
	}

	public static class Gate
	{
		public String skill;
		public int level;
		public int have;
		public boolean boostable;
		public boolean midquest;
		public String status;   // met | covered | short
		@SerializedName("train_ref") public String trainRef;
	}

	/** One lens's route for a training step. */
	public static class Plan
	{
		public String path;
		public List<Segment> segments;
		public Double hours;
		@SerializedName("attention_hours") public Double attentionHours;
		@SerializedName("gp_xp") public Double gpXp;
		@SerializedName("net_gp") public Long netGp;
		/** Positive = the route earns; net's per-hour face. */
		@SerializedName("gp_hr") public Long gpHr;
		@SerializedName("buy_now_gp") public Long buyNowGp;
		public List<Buy> buys;
		public String affordability;   // "ok" | "sell first" | "SHORT ..."
		public List<String> warnings;
		@SerializedName("tool_gaps") public List<String> toolGaps;
	}

	public static class Segment
	{
		public String name;
		@SerializedName("from") public int fromLevel;
		@SerializedName("to") public int toLevel;
		public long xp;
		public long actions;
		public double hours;
		public Double attention;
		@SerializedName("net_gp") public Long netGp;
		@SerializedName("earn_gp") public Long earnGp;
		@SerializedName("earn_note") public String earnNote;
		public String paced;
		public String note;
		public String wiki;     // wiki page title, not a URL
		public String location;
		public String travel;
		public String video;    // full URL, for technique methods
		/** Per-band consumption ("1,204 x Willow logs"); absent when input-free. */
		public List<SegInput> inputs;
	}

	public static class SegInput
	{
		public String item;
		public long need;
	}

	public static class Goals
	{
		public Goal slayer;
		public Goal combat;
		public Goal afk;
	}

	/**
	 * One goal box: xp from startXp to a gate level on the row that wants it.
	 * The panel ticks the number down from client-side xp:
	 * needed = max(0, targetXp - max(startXp, clientXp)).
	 */
	public static class Goal
	{
		public String skill;
		@SerializedName("target_level") public int targetLevel;
		@SerializedName("target_xp") public long targetXp;
		@SerializedName("start_level") public int startLevel;
		@SerializedName("start_xp") public long startXp;
		@SerializedName("xp_needed") public long xpNeeded;
		public int pos;
		public String name;
		public GoalMethod method;                          // afk goal only
		@SerializedName("method_error") public String methodError;
	}

	/** The AFK lens route's current-level segment, for the Priority AFK box. */
	public static class GoalMethod
	{
		public String name;
		public int from;
		public int to;
		public String wiki;
		public String location;
		public String travel;
	}

	public static class Buy
	{
		public String item;
		public long qty;
	}

	/** One lens's merged shopping list; sequentially netted server-side. */
	public static class Shopping
	{
		public List<ShopRow> rows;
		@SerializedName("not_buyable") public List<NotBuyable> notBuyable;
		@SerializedName("total_gp") public long totalGp;
		@SerializedName("quest_coins_gp") public Long questCoinsGp;
		public String affordability;   // "ok" | "sell first" | "SHORT ..."
		public List<String> warnings;
		/** A lens-null or errored train plan was skipped - the list understates. */
		public boolean partial;
	}

	public static class ShopRow
	{
		public String item;
		public long qty;
		public long unit;
		public long cost;
		public long limit;      // GE 4h buy limit; 0 = mapping has none
		public int cycles;      // buy cycles the merged qty needs against that limit
		@SerializedName("for") public List<ForRef> refs;   // 'for' is a Java keyword
		public List<String> choices;
		/** A caveat from the wiki's item cell - "excluding a premade fr' blast". */
		public String note;
	}

	/** An untradeable / unidentified quest shortfall - listed, never bought. */
	public static class NotBuyable
	{
		public String item;
		public int need;
		public int have;
		@SerializedName("for") public List<ForRef> refs;
		/** How to obtain it, where known - curated or from the wiki's item cell. */
		public String how;
		/** Unparsed prose, not an item - render as a requirement to read. */
		public boolean check;
	}

	/** Union like Step: kind=train has id/skill/from/to, kind=quest has pos/name. */
	public static class ForRef
	{
		public String kind;     // quest | train
		public String id;
		public String skill;
		@SerializedName("from") public Integer fromLevel;
		@SerializedName("to") public Integer toLevel;
		public Integer pos;
		public String name;
	}

	public static class Prereq
	{
		public String name;
		public String status;   // done | planned | missing
	}

	public static class ItemReq
	{
		public String name;
		public int need;
		public int have;
		public String status;   // have | short | missing | unknown | note
		public boolean optional;
		public boolean alt;
		public List<String> choices;
	}

	public static class Holdings
	{
		public String source;
		public String file;
		public String at;       // ISO timestamp of the newest snapshot
		@SerializedName("bank_at") public String bankAt;   // ...that actually saw a bank
	}

	public static class Wealth
	{
		public long coins;
		public long liquid;
	}
}

package com.osrsadvisor.plugin;

/**
 * Number formatting for the panel, matching the CLI's habits: gp abbreviated to one
 * trimmed decimal, hours to one decimal, ages coarse. Net gp reads as "profit" when
 * negative because that is what a negative net means everywhere in this workspace.
 */
final class Fmt
{
	private Fmt()
	{
	}

	static String gp(long n)
	{
		long abs = Math.abs(n);
		if (abs < 1_000)
		{
			return Long.toString(n);
		}
		if (abs < 1_000_000)
		{
			return trim(n / 1_000.0) + "k";
		}
		if (abs < 1_000_000_000)
		{
			return trim(n / 1_000_000.0) + "M";
		}
		return trim(n / 1_000_000_000.0) + "B";
	}

	/** One decimal, dropped when it is .0 - "2.1k" but "3k", not "3.0k". */
	private static String trim(double v)
	{
		String s = String.format("%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	static String netGp(long n)
	{
		return n < 0 ? "profit " + gp(-n) : "cost " + gp(n);
	}

	static String hours(double h)
	{
		return trim(h) + "h";
	}

	static String age(long seconds)
	{
		if (seconds < 60)
		{
			return "just now";
		}
		if (seconds < 3_600)
		{
			return (seconds / 60) + "m ago";
		}
		if (seconds < 86_400)
		{
			return (seconds / 3_600) + "h ago";
		}
		return (seconds / 86_400) + "d ago";
	}
}

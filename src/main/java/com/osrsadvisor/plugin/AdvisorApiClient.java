package com.osrsadvisor.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Async reads and ticks against the advisor server. The base URL is re-derived from the
 * configured ingest URL on every call - one URL in config, so the panel and the push can
 * never point at different servers, and a config edit takes effect without a restart.
 *
 * Callbacks are delivered on OkHttp's dispatcher thread; the panel marshals to the EDT.
 * The server not running is the normal case, not an error worth shouting about - it
 * surfaces as {@link ApiError.Kind#UNREACHABLE} and logs at debug only.
 */
@Slf4j
class AdvisorApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	interface ApiCallback<T>
	{
		void onSuccess(T value);

		void onError(ApiError error);
	}

	static class ApiError
	{
		enum Kind
		{
			UNREACHABLE, HTTP, BAD_RESPONSE
		}

		final Kind kind;
		final String message;
		final int code;

		ApiError(Kind kind, String message, int code)
		{
			this.kind = kind;
			this.message = message;
			this.code = code;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final AdvisorConfig config;

	AdvisorApiClient(OkHttpClient http, Gson gson, AdvisorConfig config)
	{
		this.http = http;
		this.gson = gson;
		this.config = config;
	}

	/** Scheme/host/port from the ingest URL, path replaced. Null when the config is unparseable. */
	private HttpUrl.Builder endpoint(String path)
	{
		String configured = config.url();
		HttpUrl ingest = configured == null || configured.isEmpty()
			? null : HttpUrl.parse(configured);
		if (ingest == null)
		{
			return null;
		}
		return ingest.newBuilder().encodedPath(path).query(null);
	}

	void fetchRecommendations(String player, int steps, String lens,
		ApiCallback<RecommendationsResponse> cb)
	{
		HttpUrl.Builder url = endpoint("/api/recommendations");
		if (url == null)
		{
			cb.onError(new ApiError(ApiError.Kind.BAD_RESPONSE,
				"Ingest URL in the plugin settings is not a valid URL", 0));
			return;
		}
		url.addQueryParameter("steps", Integer.toString(steps));
		if (lens != null)
		{
			url.addQueryParameter("lens", lens);
		}
		if (player != null)
		{
			url.addQueryParameter("player", player);
		}
		Request request = new Request.Builder().url(url.build()).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("advisor fetch failed: {}", e.getMessage());
				cb.onError(new ApiError(ApiError.Kind.UNREACHABLE, e.getMessage(), 0));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					String body = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						cb.onError(new ApiError(ApiError.Kind.HTTP,
							serverError(body), r.code()));
						return;
					}
					RecommendationsResponse rec =
						gson.fromJson(body, RecommendationsResponse.class);
					if (rec == null)
					{
						throw new JsonSyntaxException("empty body");
					}
					cb.onSuccess(rec);
				}
				catch (JsonSyntaxException | IOException e)
				{
					log.debug("advisor response unreadable", e);
					cb.onError(new ApiError(ApiError.Kind.BAD_RESPONSE,
						e.getMessage(), 0));
				}
			}
		});
	}

	/** POST /api/toggle {pos, done, player?} - player omitted falls back to the active account. */
	void toggle(String player, int pos, boolean done, ApiCallback<Boolean> cb)
	{
		HttpUrl.Builder url = endpoint("/api/toggle");
		if (url == null)
		{
			cb.onError(new ApiError(ApiError.Kind.BAD_RESPONSE,
				"Ingest URL in the plugin settings is not a valid URL", 0));
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pos", pos);
		body.put("done", done);
		if (player != null)
		{
			body.put("player", player);
		}
		Request request = new Request.Builder()
			.url(url.build())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("advisor toggle failed: {}", e.getMessage());
				cb.onError(new ApiError(ApiError.Kind.UNREACHABLE, e.getMessage(), 0));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						String text = r.body() != null ? r.body().string() : "";
						cb.onError(new ApiError(ApiError.Kind.HTTP,
							serverError(text), r.code()));
						return;
					}
					cb.onSuccess(true);
				}
				catch (IOException e)
				{
					cb.onError(new ApiError(ApiError.Kind.BAD_RESPONSE,
						e.getMessage(), 0));
				}
			}
		});
	}

	/** The server wraps failures as {"error": "..."} - prefer that text over a bare code. */
	private String serverError(String body)
	{
		try
		{
			RecommendationsResponse parsed =
				gson.fromJson(body, RecommendationsResponse.class);
			if (parsed != null && parsed.error != null && !parsed.error.isEmpty())
			{
				return parsed.error;
			}
		}
		catch (JsonSyntaxException ignored)
		{
		}
		return "server error";
	}
}

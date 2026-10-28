package com.world.cloudxsolution;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RequestNetworkController {
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String PUT = "PUT";
    public static final String DELETE = "DELETE";

    public static final int REQUEST_PARAM = 0;
    public static final int REQUEST_BODY = 1;

    private static final int SOCKET_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;

    protected OkHttpClient client;

    private static RequestNetworkController mInstance;

    public static synchronized RequestNetworkController getInstance() {
        if(mInstance == null) {
            mInstance = new RequestNetworkController();
        }
        return mInstance;
    }

    private synchronized OkHttpClient getClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(SOCKET_TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                    .writeTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                    .build();
        }
        return client;
    }

    public void execute(final RequestNetwork requestNetwork, String method, String url, final String tag, final RequestNetwork.RequestListener requestListener) {
        Request.Builder reqBuilder = new Request.Builder();
        Headers.Builder headerBuilder = new Headers.Builder();

        if (!requestNetwork.getHeaders().isEmpty()) {
            HashMap<String, Object> headers = requestNetwork.getHeaders();

            for (HashMap.Entry<String, Object> header : headers.entrySet()) {
                headerBuilder.add(header.getKey(), String.valueOf(header.getValue()));
            }
        }

        try {
            if (requestNetwork.getRequestType() == REQUEST_PARAM) {
                if (method.equals(GET)) {
                    HttpUrl.Builder httpBuilder;

                    try {
                        httpBuilder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
                    } catch (NullPointerException ne) {
                        throw new NullPointerException("unexpected url: " + url);
                    }

                    if (!requestNetwork.getParams().isEmpty()) {
                        HashMap<String, Object> params = requestNetwork.getParams();

                        for (HashMap.Entry<String, Object> param : params.entrySet()) {
                            httpBuilder.addQueryParameter(param.getKey(), String.valueOf(param.getValue()));
                        }
                    }

                    reqBuilder.url(httpBuilder.build()).headers(headerBuilder.build()).get();
                } else {
                    FormBody.Builder formBuilder = new FormBody.Builder();
                    if (!requestNetwork.getParams().isEmpty()) {
                        HashMap<String, Object> params = requestNetwork.getParams();

                        for (HashMap.Entry<String, Object> param : params.entrySet()) {
                            formBuilder.add(param.getKey(), String.valueOf(param.getValue()));
                        }
                    }

                    RequestBody reqBody = formBuilder.build();

                    reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody);
                }
            } else {
                RequestBody reqBody = RequestBody.create(MediaType.parse("application/json"), new Gson().toJson(requestNetwork.getParams()));

                if (method.equals(GET)) {
                    reqBuilder.url(url).headers(headerBuilder.build()).get();
                } else {
                    reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody);
                }
            }

            Request req = reqBuilder.build();

            getClient().newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull final IOException e) {
                    requestNetwork.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            requestListener.onErrorResponse(tag, e.getMessage());
                        }
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull final Response response) throws IOException {
                    try (Response closedResponse = response) {
                        if (!response.isSuccessful()) {
                            final String error = "HTTP " + response.code();
                            requestNetwork.getActivity().runOnUiThread(() -> requestListener.onErrorResponse(tag, error));
                            return;
                        }
                        final String responseBody = response.body() == null ? "" : response.body().string().trim();
                        HashMap<String, Object> headers = new HashMap<>();
                        for (String name : response.headers().names()) headers.put(name, response.header(name));
                        requestNetwork.getActivity().runOnUiThread(() -> requestListener.onResponse(tag, responseBody, headers));
                    } catch (IOException e) {
                        requestNetwork.getActivity().runOnUiThread(() -> requestListener.onErrorResponse(tag, "Network read failed"));
                    }
                }
            });
        } catch (Exception e) {
            requestListener.onErrorResponse(tag, e.getMessage());
        }
    }
}


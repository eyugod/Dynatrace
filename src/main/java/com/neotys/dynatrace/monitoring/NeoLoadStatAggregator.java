package com.neotys.dynatrace.monitoring;


import com.google.common.base.Optional;
import com.neotys.dynatrace.common.HTTPGenerator;
import com.neotys.dynatrace.monitoring.neoloadmetrics.DynatraceCustomMetric;
import com.neotys.dynatrace.monitoring.neoloadmetrics.NeoLoadDynatraceCustomMetrics;
import com.neotys.extensions.action.engine.Context;
import com.neotys.extensions.action.engine.Proxy;
import io.swagger.client.ApiException;
import io.swagger.client.api.ResultsApi;
import io.swagger.client.model.TestStatistics;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

import static com.neotys.dynatrace.common.HTTPGenerator.*;


public class NeoLoadStatAggregator extends TimerTask implements DynatraceMonitoringApi {

    private static final String DYNATRACE_API_URL = "events/";
    private static final String DYNATRACE_URL = ".live.dynatrace.com/api/v1/";
    private static final String DYNATRACE_APPLICATION = "entity/services";
    private static final String DYNATRACE_TIME_SERIES_CREATION = "timeseries/custom";
    private static final String NL_TIMESERIES_PREFIX = "neoload.";
    private static final String DYNATRACE_NEW_DATA = "entity/infrastructure/custom/";
    private static final String DYNATRACE_TIME_SERIES = "timeseries";
    private static final String HTTPS = "https://";
    private static final String NEOLOAD_SAAS_NEOTYS_COM = "neoload.saas.neotys.com";
    private static final String NEOLOAD_URL_LAST = "/#!result/overview/?benchId=";
    private static final String DYNATRACE_PROTOCOL = "https://";
    private static final String NEOLOADL_GUID = "com.neotys.NeoLoad.plugin";
    private static final String VERSION = "1.0.0";
    private static final String NL_PICTURE_URL = "http://www.neotys.com/wp-content/uploads/2017/07/Neotys-Emblem-Primary.png";
    private static final String NEOLOAD_TYPE = "NeoLoad";

    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 403;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int REQUEST_ENTITY_TOO_LARGE = 413;
    private static final int INTERNAL_SERVER_ERROR = 500;
    private static final int BAD_GATEWAY = 502;
    private static final int SERVICE_UNAVAIBLE = 503;
    private static final int GATEWAY_TIMEOUT = 504;
    private static final int HTTP_RESPONSE = 200;
    private static final int HTTP_RESPONSE_CREATED = 201;
    private static final int HTTP_RESPONSE_ALREADY = 200;
    private static final int MIN_DYNATRACE_DURATION = 30;

    private final Optional<String> proxyName;
    private final Context context;

    private HTTPGenerator httpGenerator;
    private ResultsApi nlWebResult;

    private HashMap<String, String> headerMap = null;
    private String componentsName;
    private String dynatraceApiKey;
    private String dynatraceAccountId;
    private String testName;
    private final String testId;
    private String applicationEntityId;
    private String scenarioName;
    private Optional<String> dynatraceManagedHostName;
    private String dataExchangeApiUrl;
    private boolean timeSeriesConfigured = false;
    private long lastDuration = 0;

    private void initHttpClient() {
        headerMap = new HashMap<>();
        //	headerMap.put("X-License-Key", NewRElicLicenseKey);
        //headerMap.put("Content-Type", "application/json");
        //headerMap.put("Accept","application/json");

    }

    public void addTokenIngetParam(final Map<String, String> param) {
        param.put("Api-Token", dynatraceApiKey);
    }

    public NeoLoadStatAggregator(final String dynatraceApiKey,
                                 final String dynatraceAccountId,
                                 final ResultsApi nlWebResult,
                                 final Context context,
                                 final String dataExchangeApiUrl,
                                 final Optional<String> dynatraceManagedHostName,
                                 final Optional<String> proxyName) {
        this.proxyName = proxyName;
        componentsName = "Statistics";
        this.dynatraceApiKey = dynatraceApiKey;
        this.context = context;
        this.testId = context.getTestId();
        this.testName = context.getTestName();
        this.nlWebResult = nlWebResult;
        this.dynatraceManagedHostName = dynatraceManagedHostName;
        this.dynatraceAccountId = dynatraceAccountId;
        this.scenarioName = context.getScenarioName();
        this.dataExchangeApiUrl = dataExchangeApiUrl;
        initHttpClient();
    }

    private void sendStatsToDynatrace() throws ApiException, DynatraceStatException, IOException, URISyntaxException {
        TestStatistics statsResult;
        long utc = System.currentTimeMillis() / 1000;

        if (lastDuration == 0 || (utc - lastDuration) >= MIN_DYNATRACE_DURATION) {
            statsResult = nlWebResult.getTestStatistics(testId);
            if (statsResult != null) {
                lastDuration = sendData(statsResult);
            } else {
                context.getLogger().debug("No stats found in NeoLoad web API.");
            }
        }
    }


    public long sendData(final TestStatistics testStatistics)
            throws DynatraceStatException, IOException, ApiException, URISyntaxException {
        long utc = System.currentTimeMillis() / 1000;

        NeoLoadDynatraceCustomMetrics.updateTimeseriesToSend(testStatistics);

        if (!timeSeriesConfigured) {
            if (hasCustomMetric(NeoLoadDynatraceCustomMetrics.getTimeseriesToSend().get(NeoLoadDynatraceCustomMetrics.REQUEST_COUNT)) != HTTP_RESPONSE) {
                for (DynatraceCustomMetric dynatraceTimeseries : NeoLoadDynatraceCustomMetrics.getTimeseriesToSend().values()) {
                    //Register metrics
                    registerCustomMetric(dynatraceTimeseries);
                }
            }
            timeSeriesConfigured = true;
        }

        //Report activity
        reportCustomMetrics(new ArrayList(NeoLoadDynatraceCustomMetrics.getTimeseriesToSend().values()));

        return utc;
    }

    private long getUtcDate() {
        long timeInMillisSinceEpoch123 = System.currentTimeMillis();
        timeInMillisSinceEpoch123 -= 200000;
        return timeInMillisSinceEpoch123;
    }

    private String getApiUrl() {
        if (dynatraceManagedHostName.isPresent()) {
            return DYNATRACE_PROTOCOL + dynatraceManagedHostName.get() + "/api/v1/";
        } else {
            return DYNATRACE_PROTOCOL + dynatraceAccountId + DYNATRACE_URL;
        }
    }

    private String getNlUrl() {
        // TODO get nl web front URL from context.
        return HTTPS + NEOLOAD_SAAS_NEOTYS_COM + NEOLOAD_URL_LAST;
    }


    @Override
    public void run() {
        try {
            sendStatsToDynatrace();
        } catch (ApiException | DynatraceStatException | IOException | URISyntaxException e) {
            context.getLogger().error("Error while sending stats to Dynatrace", e);
        }
    }

    private Optional<Proxy> getProxy(final Optional<String> proxyName, final String url) throws MalformedURLException {
        if (proxyName.isPresent()) {
            return Optional.fromNullable(context.getProxyByName(proxyName.get(), new URL(url)));
        }
        return Optional.absent();
    }

    @Override
    public int registerCustomMetric(DynatraceCustomMetric dynatraceCustomMetric) throws IOException, URISyntaxException {
        int httpCode = 0;
        final Map<String, String> head = new HashMap<>();
        final Map<String, String> parameters = new HashMap<>();
        final String timeSeriesName = dynatraceCustomMetric.getDimensions().get(0);
        final String url = getApiUrl() + DYNATRACE_TIME_SERIES_CREATION + ":" + timeSeriesName;
        addTokenIngetParam(parameters);

        final String jsonString = "{\"displayName\":\"" + dynatraceCustomMetric.getDisplayName() + "\","
                + "\"unit\":\"" + dynatraceCustomMetric.getUnit() + "\","
                + "\"dimensions\": [\"Neoload\"],"
                + "\"types\":[\"" + dynatraceCustomMetric.getTypes().get(0) + "\"]}";

        final Optional<Proxy> proxy = getProxy(proxyName, url);
        final HTTPGenerator insightHttp = HTTPGenerator.newJsonHttpGenerator(HTTP_PUT_METHOD, url, head, parameters, proxy, jsonString);

        try {
            httpCode = insightHttp.executeAndGetResponseCode();
        } finally {
            insightHttp.closeHttpClient();
        }
        if (httpCode == HTTP_RESPONSE_CREATED) {
            dynatraceCustomMetric.setCreated(true);
        }
        return httpCode;
    }


    @Override
    public int reportCustomMetrics(final List<DynatraceCustomMetric> dynatraceCustomMetrics) throws IOException, URISyntaxException, DynatraceStatException {
        int httpCode = 0;
        final Map<String, String> head = new HashMap<>();
        final Map<String, String> parameters = new HashMap<>();
        HTTPGenerator insightHttp;

        addTokenIngetParam(parameters);

        String url = getApiUrl() + DYNATRACE_NEW_DATA + "NeoLoadData";
        String exceptionMessage = null;
        long time = System.currentTimeMillis();

        // TODO we need to get controller host and put in ipAddresses.
        String jsonString = "{\"displayName\" : \"NeoLoad Data\","
                + "\"ipAddresses\" : [\"" + dataExchangeApiUrl + "\"],"
                + "\"listenPorts\" : [\"" + 7400 + "\"],"
                + "\"type\" : \"" + NEOLOAD_TYPE + "\","
                + "\"favicon\" : \"" + NL_PICTURE_URL + "\","
                + "\"configUrl\" : \"" + getNlUrl() + testId + "\","
                + "\"tags\": [\"Loadtest\", \"NeoLoad\"],"
                + "\"properties\" : { \"TestName\" : \"" + testName + "\" ,\"ScenarioName\" : \"" + scenarioName + "\"  },"
                + "\"series\" : [";


        boolean hasMetrics = false;
        for (DynatraceCustomMetric dynatraceCustomMetric : dynatraceCustomMetrics) {
            if (dynatraceCustomMetric.isCreated() && dynatraceCustomMetric.isValued()) {
                String conStr = "{"
                        + "\"timeseriesId\" : \"custom:" + dynatraceCustomMetric.getDimensions().get(0) + "\","
                        + "\"dimensions\" : { \"Neoload\" : \"" + dynatraceCustomMetric.getDisplayName() + "\"  },"
                        + "\"dataPoints\" : [ [" + String.valueOf(time) + "  , " + dynatraceCustomMetric.getValue() + " ] ]"
                        + "}";


                jsonString += conStr + ",";
                hasMetrics = true;
            }
        }

        if (",".equalsIgnoreCase(jsonString.substring(jsonString.length() - 1))) {
            jsonString = jsonString.substring(0, jsonString.length() - 1);
        }

        jsonString += "]}";

        if (hasMetrics) {

            final Optional<Proxy> proxy = getProxy(proxyName, url);
            insightHttp = HTTPGenerator.newJsonHttpGenerator(HTTP_POST_METHOD, url, head, parameters, proxy, jsonString);

            try {
                httpCode = insightHttp.executeAndGetResponseCode();
            } finally {
                insightHttp.closeHttpClient();
            }
            switch (httpCode) {
                case BAD_REQUEST:
                    exceptionMessage = "The request or headers are in the wrong format, or the URL is incorrect, or the GUID does not meet the validation requirements.";
                    break;
                case UNAUTHORIZED:
                    exceptionMessage = "Authentication error (no license key header, or invalid license key).";
                    break;
                case NOT_FOUND:
                    exceptionMessage = "Invalid URL.";
                    break;
                case METHOD_NOT_ALLOWED:
                    exceptionMessage = "Returned if the method is an invalid or unexpected type (GET/POST/PUT/etc.).";
                    break;
                case REQUEST_ENTITY_TOO_LARGE:
                    exceptionMessage = "Too many metrics were sent in one request, or too many components (instances) were specified in one request, or other single-request limits were reached.";
                    break;
                case INTERNAL_SERVER_ERROR:
                    exceptionMessage = "Unexpected server error";
                    break;
                case BAD_GATEWAY:
                    exceptionMessage = "All 50X errors mean there is a transient problem in the server completing requests, and no data has been retained. Clients are expected to resend the data after waiting one minute. The data should be aggregated appropriately, combining multiple timeslice data values for the same metric into a single aggregate timeslice data value.";
                    break;
                case SERVICE_UNAVAIBLE:
                    exceptionMessage = "All 50X errors mean there is a transient problem in the server completing requests, and no data has been retained. Clients are expected to resend the data after waiting one minute. The data should be aggregated appropriately, combining multiple timeslice data values for the same metric into a single aggregate timeslice data value.";
                    break;
                case GATEWAY_TIMEOUT:
                    exceptionMessage = "All 50X errors mean there is a transient problem in the server completing requests, and no data has been retained. Clients are expected to resend the data after waiting one minute. The data should be aggregated appropriately, combining multiple timeslice data values for the same metric into a single aggregate timeslice data value.";
                    break;

            }
            if (exceptionMessage != null) {
                throw new DynatraceStatException(exceptionMessage);
            }
        }
        return httpCode;
    }

    @Override
    public int hasCustomMetric(final DynatraceCustomMetric dynatraceCustomMetric) throws IOException, URISyntaxException {
        int httpCode;
        final String url = getApiUrl() + DYNATRACE_TIME_SERIES;
        final Map<String, String> parameters = new HashMap<>();
        final String timeSeriesName = dynatraceCustomMetric.getDimensions().get(0);
        addTokenIngetParam(parameters);
        parameters.put("timeseriesId", NL_TIMESERIES_PREFIX + ":" + timeSeriesName);
        parameters.put("startTimestamp", String.valueOf(getUtcDate()));
        parameters.put("endTimestamp", String.valueOf(System.currentTimeMillis()));

        final Optional<Proxy> proxy = getProxy(proxyName, url);
        httpGenerator = new HTTPGenerator(HTTP_GET_METHOD, url, headerMap, parameters, proxy);

        try {
            httpCode = httpGenerator.executeAndGetResponseCode();
        } finally {
            httpGenerator.closeHttpClient();
        }

        return httpCode;
    }
}


package io.github.daluu.yajp.listeners;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.JvmAttributeGaugeSet;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import com.codahale.metrics.riemann.Riemann;
import com.codahale.metrics.riemann.RiemannReporter;
import com.codahale.metrics.servlets.AdminServlet;
import com.codahale.metrics.servlets.MetricsServlet;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;

//modified from original source from http://theworkaholic.blogspot.com/2015/05/graphs-for-jmeter-using-elasticsearch.html

/* DropWizard Metrics by default, & with 3rd party support, supports sending metrics to multiple destinations
 * via "reporters". Extend this client if you wish to add more destinations than currently offered.
 * NOTE: there's a native CsvReporter but that's kind of futile since JMeter results kind of gives you about the same thing
 * 
 * Some suggested reporters to add, should you wish (and perhaps do a PR back to this project): 
 * metrics-statsd, metrics-influxdb, metrics-cassandra
 * metrics-new-relic, metrics-elasticsearch-reporter
 */

public class DropWizardMetricsBackendListenerClient extends AbstractBackendListenerClient {
	private MetricRegistry registry;
	private int webEndpointPort;
	private Undertow server;
	private Logger logger; 
	private String dateTimeAppendFormat;
	private String sampleType;
	private String runId;
	private long offset;
	private boolean enableRiemannMetrics;
	private String riemannHost;
	private int riemannPort;
	private String riemannMetricPrefix;
	private RiemannReporter riemannReporter;
	private boolean enableGraphiteMetrics;
	private String graphiteHost;
	private int graphitePort;
	private String graphiteMetricPrefix;
	private GraphiteReporter graphiteReporter;
	private static final String DEFAULT_RIEMANN_METRIC_OPTION = "${__P(riemann,0)}";
	private static final String DEFAULT_RIEMANN_HOST = "${__P(riemannhost,localhost)}";
	private static final String DEFAULT_RIEMANN_PORT = "${__P(riemannport,5555)}";
	private static final String DEFAULT_RIEMANN_METRIC_PREFIX = "${__P(riemannprefix,jmeter)}";
	private static final String DEFAULT_GRAPHITE_METRIC_OPTION = "${__P(graphite,0)}";
	private static final String DEFAULT_GRAPHITE_HOST = "${__P(graphitehost,localhost)}";
	private static final String DEFAULT_GRAPHITE_PORT = "${__P(graphiteport,2003)}";
	private static final String DEFAULT_GRAPHITE_METRIC_PREFIX = "${__P(graphiteprefix,jmeter)}";
	private static final String DEFAULT_WEB_PORT = "${__P(webport,8888)}";
	private static final String TIMESTAMP = "timestamp";
	private static final String VAR_DELIMITER = "~";
	private static final String VALUE_DELIMITER = "=";
	private boolean initState = true;
	private Map<String, Object> metricsMap = new HashMap<String, Object>();
	
	@Override
	public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
		for(SampleResult result : results) {
			Map<String,Object> metrics = getMap(result);
			Map<String,Metric> metricStore = registry.getMetrics();
			for (Map.Entry<String, Object> entry : metrics.entrySet())
			{
				String key = entry.getKey();
		    	long numMetricValue = 0;
		    	boolean isNumeric = false;
		    	
		    	try{
		    		numMetricValue = Long.parseLong(entry.getValue().toString());
		    		isNumeric = true;
		    	}catch(NumberFormatException e){
		    		isNumeric = false;
		    	}
		    	
		    	if(initState){ //set up metrics registry store to publish metrics to
		    		//change logic here if you think there could be improvements made on how the metric should be reported
		    		//e.g. metric type - meter vs histogram vs gauge vs counter
		    		if(isNumeric){
		    			if(key.contains("Time")){
		    				Histogram hist = registry.histogram(key); //for latencies
		    				hist.update(numMetricValue);
		    				
		    			}else{
		    				Meter mete = registry.meter(key); //for rate of change/requests/messages/etc.
		    				mete.mark(numMetricValue);
		    			}
		    		}else{
		    			registry.register(key,
		    					new Gauge<String>() {
		            		@Override
		            		public String getValue() {
		            			return metricsMap.get(key).toString();
		            		}
		        		});
		    		}
		    	}else{
		    		for (Map.Entry<String, Metric> metricEntry : metricStore.entrySet()){
		    			String metricKey = metricEntry.getKey();
		    			if(metricKey == key){
		    				Metric val = metricEntry.getValue();
		    				if(val instanceof Meter){
		    					((Meter) val).mark(numMetricValue);
		    				}else if(val instanceof Histogram){
		    					((Histogram) val).update(numMetricValue);
		    				}else if(val instanceof Gauge){
		    					//no need to do anything for gauges?
		    				}else{
		    					logger.warn("Unexpected metric type found for metric named: " + metricKey);
		    				}
		    			}
		    		}
		    	}
			}
			if(initState){ //we can just use the 1st result to init the metrics registry store
				initState = false;
			}
		}
	}
	
	private Map<String, Object> getMap(SampleResult result) {
		
		//core metrics most (likely) useful to track
		metricsMap.put("AllThreads", result.getAllThreads());
		metricsMap.put("BodySize", result.getBodySize());
		metricsMap.put("Bytes", result.getBytes());
		metricsMap.put("ConnectTime", result.getConnectTime());
		metricsMap.put("ElapsedTime", result.getTime());
		metricsMap.put("ErrorCount", result.getErrorCount());
		metricsMap.put("GroupThreads", result.getGroupThreads());
		metricsMap.put("Latency", result.getLatency());
		metricsMap.put("ResponseCode", result.getResponseCode());
		metricsMap.put("ResponseTime", result.getTime());
		metricsMap.put("SampleCount", result.getSampleCount());
		
		//perhaps less useful metrics since these are typically non-numeric
		metricsMap.put("ContentType", result.getContentType());
		metricsMap.put("DataType", result.getDataType());
		metricsMap.put("EndTime", new Date(result.getEndTime()));
		//metricsMap.put("FailureMessage", result.get);
		//metricsMap.put("HostName", result.get);
		metricsMap.put("IdleTime", result.getIdleTime());
		metricsMap.put("NormalizedTimestamp", new Date(result.getTimeStamp() - offset));
		metricsMap.put("ResponseMessage", result.getResponseMessage());
		metricsMap.put("RunId", runId);
		
		String[] sampleLabels = result.getSampleLabel().split(VAR_DELIMITER);
		metricsMap.put("SampleLabel", sampleLabels[0]);
		for(int i=1;i<sampleLabels.length;i++) {
			String[] varNameAndValue =sampleLabels[i].split(VALUE_DELIMITER);
			metricsMap.put(varNameAndValue[0], varNameAndValue[1]);
		}
		
		metricsMap.put("StartTime", new Date(result.getStartTime()));
		metricsMap.put("Success", String.valueOf(result.isSuccessful()));
		metricsMap.put(TIMESTAMP, new Date(result.getTimeStamp()));
		metricsMap.put("ThreadName", result.getThreadName());
		metricsMap.put("URL", result.getUrlAsString());
		
		//TODO assertion results
		/*
		AssertionResult[] assertions = result.getAssertionResults();
		int count=0;
		if(assertions != null) {
			Map<String,Object> [] assertionArray = new HashMap[assertions.length];
			for(AssertionResult assertionResult : assertions) {
				Map<String,Object> assertionMap = new HashMap<String,Object>();
				assertionMap.put("Failure", assertionResult.isError() || assertionResult.isFailure());
				assertionMap.put("FailureMessage", assertionResult.getFailureMessage());
				assertionMap.put("Name", assertionResult.getName());
				assertionArray[count++] = assertionMap;
			}
			metricsMap.put("Assertions", assertionArray);
		}
		*/
		return metricsMap;
	}

	@Override
	public void setupTest(BackendListenerContext context) throws Exception {
		initState = true;
		//logger = getLogger();
		logger = LoggingManager.getLoggerForClass();
		webEndpointPort = Integer.valueOf(context.getParameter("webEndpointPort"));
		enableRiemannMetrics = context.getParameter("enableMetricsToRiemann").equals("1");
		riemannHost = context.getParameter("riemannHost");
        riemannPort = Integer.valueOf(context.getParameter("riemannPort"));
        riemannMetricPrefix = context.getParameter("riemannMetricPrefix");
        String dbgMsg = "Riemann enabled?: "+this.enableRiemannMetrics+", host: "+this.riemannHost
        		+", port: "+this.riemannPort+", metric prefix: '"+riemannMetricPrefix+"'";
        logger.debug(dbgMsg);
        System.out.println(dbgMsg);
        enableGraphiteMetrics = context.getParameter("enableMetricsToGraphite").equals("1");
		graphiteHost = context.getParameter("graphiteHost");
        graphitePort = Integer.valueOf(context.getParameter("graphitePort"));
        graphiteMetricPrefix = context.getParameter("graphiteMetricPrefix");
        dbgMsg = "Graphite enabled?: "+this.enableRiemannMetrics+", host: "+this.graphiteHost
        		+", port: "+this.graphitePort+", metric prefix: '"+graphiteMetricPrefix+"'";
        logger.debug(dbgMsg);
        System.out.println(dbgMsg);
        
        this.registry = new MetricRegistry();
        registry.registerAll(new MemoryUsageGaugeSet());
        registry.registerAll(new ClassLoadingGaugeSet());
        registry.registerAll(new ThreadStatesGaugeSet());
        registry.registerAll(new JvmAttributeGaugeSet()); //for uptime metric, http://www.glitchwrks.com/2015/01/23/dropwizard-uptime
        
        if(enableRiemannMetrics){
        	try {
                Riemann riemann = new Riemann(riemannHost, riemannPort, 10);
                riemannReporter = RiemannReporter.forRegistry(registry)
                        .prefixedWith(riemannMetricPrefix)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(TimeUnit.MILLISECONDS)
                        .filter(MetricFilter.ALL)
                        .useSeparator(".")
                        .build(riemann);
                riemannReporter.start(1, TimeUnit.MINUTES);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if(enableGraphiteMetrics){
        	final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHost, graphitePort));
    		//prefix to include hostname since graphite has no notion of host grouping/filtering unlike riemann
    		graphiteReporter = GraphiteReporter.forRegistry(registry)
    				.prefixedWith(graphiteMetricPrefix+"."+graphiteHost)
    				.convertRatesTo(TimeUnit.SECONDS)
    				.convertDurationsTo(TimeUnit.MILLISECONDS)
    				.filter(MetricFilter.ALL)
    				.build(graphite);
    		graphiteReporter.start(1, TimeUnit.MINUTES);
        }
        
        
        //MetricsServlet metricsServlet = new MetricsServlet(registry);
        DeploymentInfo servletBuilder = Servlets.deployment()
                .setClassLoader(this.getClass().getClassLoader())
                .setContextPath("/")
                .addServletContextAttribute(MetricsServlet.METRICS_REGISTRY, registry)
                .setDeploymentName("jmeter_metrics.war")
                .addServlets(
                        Servlets.servlet("Admin", AdminServlet.class)
                                .addMapping("/*")
                );

        DeploymentManager manager = Servlets.defaultContainer().addDeployment(servletBuilder);
        manager.deploy();
        PathHandler path = null;
        try {
            path = Handlers.path()
                    .addPrefixPath("/", manager.start());
        } catch (ServletException e) {
            e.printStackTrace();
        }
        
        //get hostname to bind web endpoint to
        final String hostname;
        String _hostname = null;
        try{
        	_hostname = InetAddress.getLocalHost().getHostName();
        }catch(Exception ex){
        	_hostname = System.getenv("HOSTNAME");
        }
        hostname = _hostname;
        
        if(hostname != null){
        	server = Undertow.builder()
                    .addHttpListener(this.webEndpointPort, "localhost")
                    .addHttpListener(this.webEndpointPort, hostname)
                    .setHandler(path)
                    .build();
        	
        	registry.register("hostname",
		            new Gauge<String>() {
		                @Override
		                public String getValue() {
		                    return hostname;
		                }
		            });
        }else{
        	server = Undertow.builder()
                    .addHttpListener(this.webEndpointPort, "localhost")
                    .setHandler(path)
                    .build();
        }
        server.start();
        
        dateTimeAppendFormat = context.getParameter("dateTimeAppendFormat");
        if(dateTimeAppendFormat!=null && dateTimeAppendFormat.trim().equals("")) {
        	dateTimeAppendFormat = null;
        }
        sampleType = context.getParameter("sampleType");
		String normalizedTime = context.getParameter("normalizedTime");
		if(normalizedTime != null && normalizedTime.trim().length() > 0 ){
			SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX");
			Date d = sdf2.parse(normalizedTime);
			long normalizedDate = d.getTime();
			Date now = new Date();
			offset = now.getTime() - normalizedDate;
		}
		runId = context.getParameter("runId");
		
		super.setupTest(context);
	}

	@Override
	public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        //core required parameters
        arguments.addArgument("webEndpointPort", DEFAULT_WEB_PORT);
        arguments.addArgument("enableMetricsToRiemann", DEFAULT_RIEMANN_METRIC_OPTION);
        arguments.addArgument("riemannHost", DEFAULT_RIEMANN_HOST);
        arguments.addArgument("riemannPort", DEFAULT_RIEMANN_PORT);
        arguments.addArgument("riemannMetricPrefix", DEFAULT_RIEMANN_METRIC_PREFIX);
        arguments.addArgument("enableMetricsToGraphite", DEFAULT_GRAPHITE_METRIC_OPTION);
        arguments.addArgument("graphiteHost", DEFAULT_GRAPHITE_HOST);
        arguments.addArgument("graphitePort", DEFAULT_GRAPHITE_PORT);
        arguments.addArgument("graphiteMetricPrefix", DEFAULT_GRAPHITE_METRIC_PREFIX);
        //parameters from original code this was based on, not really used...
        arguments.addArgument("summaryOnly", "true");
        arguments.addArgument("samplersList", "");
        arguments.addArgument("sampleType", "SampleResult");
        arguments.addArgument("dateTimeAppendFormat", "-yyyy-MM-DD");
        arguments.addArgument("normalizedTime","2015-01-01 00:00:00.000-00:00");
        arguments.addArgument("runId", "${__UUID()}");
        return arguments;
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {
		super.teardownTest(context);
		riemannReporter.stop();
		graphiteReporter.stop();
		server.stop();
	}

}

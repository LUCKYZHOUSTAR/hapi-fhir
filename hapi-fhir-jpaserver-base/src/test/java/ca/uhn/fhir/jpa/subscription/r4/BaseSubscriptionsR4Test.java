package ca.uhn.fhir.jpa.subscription.r4;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.DaoRegistry;
import ca.uhn.fhir.jpa.provider.r4.BaseResourceProviderR4Test;
import ca.uhn.fhir.jpa.subscription.BaseSubscriptionInterceptor;
import ca.uhn.fhir.jpa.subscription.RestHookTestDstu2Test;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.PortUtil;
import com.google.common.collect.Lists;
import net.ttddyy.dsproxy.QueryCount;
import net.ttddyy.dsproxy.listener.SingleQueryCountHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.junit.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Ignore
public abstract class BaseSubscriptionsR4Test extends BaseResourceProviderR4Test {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseSubscriptionsR4Test.class);


	private static int ourListenerPort;
	private static RestfulServer ourListenerRestServer;
	private static Server ourListenerServer;
	protected static List<String> ourContentTypes = Collections.synchronizedList(new ArrayList<>());
	protected static List<String> ourHeaders = Collections.synchronizedList(new ArrayList<>());
	private static SingleQueryCountHolder ourCountHolder;

	@Autowired
	private SingleQueryCountHolder myCountHolder;
	@Autowired
	protected DaoConfig myDaoConfig;
	@Autowired
	private DaoRegistry myDaoRegistry;

	protected CountingInterceptor myCountingInterceptor;

	protected static List<Observation> ourCreatedObservations = Collections.synchronizedList(Lists.newArrayList());
	protected static List<Observation> ourUpdatedObservations = Collections.synchronizedList(Lists.newArrayList());
	protected static String ourListenerServerBase;

	protected List<IIdType> mySubscriptionIds = Collections.synchronizedList(new ArrayList<>());


	@After
	public void afterUnregisterRestHookListener() {
		BaseSubscriptionInterceptor.setForcePayloadEncodeAndDecodeForUnitTests(false);

		for (IIdType next : mySubscriptionIds) {
			IIdType nextId = next.toUnqualifiedVersionless();
			ourLog.info("Deleting: {}", nextId);
			ourClient.delete().resourceById(nextId).execute();
		}
		mySubscriptionIds.clear();

		myDaoConfig.setAllowMultipleDelete(true);
		ourLog.info("Deleting all subscriptions");
		ourClient.delete().resourceConditionalByUrl("Subscription?status=active").execute();
		ourClient.delete().resourceConditionalByUrl("Observation?code:missing=false").execute();
		ourLog.info("Done deleting all subscriptions");
		myDaoConfig.setAllowMultipleDelete(new DaoConfig().isAllowMultipleDelete());

		ourRestServer.unregisterInterceptor(getRestHookSubscriptionInterceptor());
	}

	@Before
	public void beforeRegisterRestHookListener() {
		ourRestServer.registerInterceptor(getRestHookSubscriptionInterceptor());
	}

	@Before
	public void beforeReset() throws Exception {
		ourCreatedObservations.clear();
		ourUpdatedObservations.clear();
		ourContentTypes.clear();
		ourHeaders.clear();

		// Delete all Subscriptions
		Bundle allSubscriptions = ourClient.search().forResource(Subscription.class).returnBundle(Bundle.class).execute();
		for (IBaseResource next : BundleUtil.toListOfResources(myFhirCtx, allSubscriptions)) {
			ourClient.delete().resource(next).execute();
		}
		waitForRegisteredSubscriptionCount(0);

		ExecutorSubscribableChannel processingChannel = (ExecutorSubscribableChannel) getRestHookSubscriptionInterceptor().getProcessingChannel();
		processingChannel.setInterceptors(new ArrayList<>());
		myCountingInterceptor = new CountingInterceptor();
		processingChannel.addInterceptor(myCountingInterceptor);
	}


	protected Subscription createSubscription(String theCriteria, String thePayload) throws InterruptedException {
		Subscription subscription = newSubscription(theCriteria, thePayload);

		MethodOutcome methodOutcome = ourClient.create().resource(subscription).execute();
		subscription.setId(methodOutcome.getId().getIdPart());
		mySubscriptionIds.add(methodOutcome.getId());

		return subscription;
	}

	protected Subscription newSubscription(String theCriteria, String thePayload) {
		Subscription subscription = new Subscription();
		subscription.setReason("Monitor new neonatal function (note, age will be determined by the monitor)");
		subscription.setStatus(Subscription.SubscriptionStatus.REQUESTED);
		subscription.setCriteria(theCriteria);

		Subscription.SubscriptionChannelComponent channel = subscription.getChannel();
		channel.setType(Subscription.SubscriptionChannelType.RESTHOOK);
		channel.setPayload(thePayload);
		channel.setEndpoint(ourListenerServerBase);
		return subscription;
	}


	protected void waitForQueueToDrain() throws InterruptedException {
		RestHookTestDstu2Test.waitForQueueToDrain(getRestHookSubscriptionInterceptor());
	}

	@PostConstruct
	public void initializeOurCountHolder() {
		ourCountHolder = myCountHolder;
	}


	protected Observation sendObservation(String code, String system) {
		Observation observation = new Observation();
		CodeableConcept codeableConcept = new CodeableConcept();
		observation.setCode(codeableConcept);
		Coding coding = codeableConcept.addCoding();
		coding.setCode(code);
		coding.setSystem(system);

		observation.setStatus(Observation.ObservationStatus.FINAL);

		MethodOutcome methodOutcome = ourClient.create().resource(observation).execute();

		String observationId = methodOutcome.getId().getIdPart();
		observation.setId(observationId);

		return observation;
	}



	public static class ObservationListener implements IResourceProvider {

		@Create
		public MethodOutcome create(@ResourceParam Observation theObservation, HttpServletRequest theRequest) {
			ourLog.info("Received Listener Create");
			ourContentTypes.add(theRequest.getHeader(ca.uhn.fhir.rest.api.Constants.HEADER_CONTENT_TYPE).replaceAll(";.*", ""));
			ourCreatedObservations.add(theObservation);
			extractHeaders(theRequest);
			return new MethodOutcome(new IdType("Observation/1"), true);
		}

		private void extractHeaders(HttpServletRequest theRequest) {
			java.util.Enumeration<String> headerNamesEnum = theRequest.getHeaderNames();
			while (headerNamesEnum.hasMoreElements()) {
				String nextName = headerNamesEnum.nextElement();
				Enumeration<String> valueEnum = theRequest.getHeaders(nextName);
				while (valueEnum.hasMoreElements()) {
					String nextValue = valueEnum.nextElement();
					ourHeaders.add(nextName + ": " + nextValue);
				}
			}
		}

		@Override
		public Class<? extends IBaseResource> getResourceType() {
			return Observation.class;
		}

		@Update
		public MethodOutcome update(@ResourceParam Observation theObservation, HttpServletRequest theRequest) {
			ourLog.info("Received Listener Update");
			ourUpdatedObservations.add(theObservation);
			ourContentTypes.add(theRequest.getHeader(Constants.HEADER_CONTENT_TYPE).replaceAll(";.*", ""));
			extractHeaders(theRequest);
			return new MethodOutcome(new IdType("Observation/1"), false);
		}

	}

	@AfterClass
	public static void reportTotalSelects() {
		ourLog.info("Total database select queries: {}", getQueryCount().getSelect());
	}

	private static QueryCount getQueryCount() {
		return ourCountHolder.getQueryCountMap().get("");
	}

	@BeforeClass
	public static void startListenerServer() throws Exception {
		ourListenerPort = PortUtil.findFreePort();
		ourListenerRestServer = new RestfulServer(FhirContext.forR4());
		ourListenerServerBase = "http://localhost:" + ourListenerPort + "/fhir/context";

		ObservationListener obsListener = new ObservationListener();
		ourListenerRestServer.setResourceProviders(obsListener);

		ourListenerServer = new Server(ourListenerPort);

		ServletContextHandler proxyHandler = new ServletContextHandler();
		proxyHandler.setContextPath("/");

		ServletHolder servletHolder = new ServletHolder();
		servletHolder.setServlet(ourListenerRestServer);
		proxyHandler.addServlet(servletHolder, "/fhir/context/*");

		ourListenerServer.setHandler(proxyHandler);
		ourListenerServer.start();
	}

	@AfterClass
	public static void stopListenerServer() throws Exception {
		ourListenerServer.stop();
	}

}

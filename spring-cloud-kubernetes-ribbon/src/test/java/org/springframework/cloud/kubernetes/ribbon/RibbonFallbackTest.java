/*
 * Copyright (C) 2016 to the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.kubernetes.ribbon;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.server.mock.KubernetesServer;
import io.fabric8.mockwebserver.DefaultMockServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestTemplate;

import static org.junit.Assert.fail;

/**
 * @author <a href="mailto:cmoullia@redhat.com">Charles Moulliard</a>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class,
                properties = {
	"spring.application.name=testapp",
	"spring.cloud.kubernetes.client.namespace=testns",
	"spring.cloud.kubernetes.client.trustCerts=true",
	"spring.cloud.kubernetes.config.namespace=testns"})
@EnableAutoConfiguration
@EnableDiscoveryClient
public class RibbonFallbackTest {

	@ClassRule
	public static KubernetesServer mockServer = new KubernetesServer(false);

	public static DefaultMockServer mockEndpoint;

	public static KubernetesClient mockClient;

	private static final Log LOG = LogFactory.getLog(RibbonFallbackTest.class);

	@Value("${service.occurence}")
	private int SERVICE_OCCURENCE;

	@Autowired
	RestTemplate restTemplate;

	@BeforeClass
	public static void setUpBefore() throws Exception {
		mockClient = mockServer.getClient();

		//Configure the kubernetes master url to point to the mock server
		System.setProperty(Config.KUBERNETES_MASTER_SYSTEM_PROPERTY, mockClient.getConfiguration().getMasterUrl());
		System.setProperty(Config.KUBERNETES_TRUST_CERT_SYSTEM_PROPERTY, "true");
		System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
		System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");

		mockEndpoint = new DefaultMockServer(false);
		mockEndpoint.start();
	}

	@Test
	public void testFallBackGreetingEndpoint() {
		/**
		 *  Scenario tested
		 *  1. Register the mock endpoint of the service into KubeMockServer and call /greeting service
		 *  2. Unregister the mock endpoint and verify that Ribbon doesn't have any instances anymore in its list
		 *  3. Re register the mock endpoint and play step 1)
		 **/

		LOG.info(">>>>>>>>>> BEGIN PART 1 <<<<<<<<<<<<<");
		// As Ribbon refreshes its list every 500ms, we will configure the Kube Server endpoint to reply to at least x attempts
		// to be sure that Ribbon will get the mockendpoint to access it for the call
		mockServer.expect().get()
			.withPath("/api/v1/namespaces/testns/endpoints/testapp")
			.andReturn(200, newEndpoint("testapp-a","testns", mockEndpoint)).times(SERVICE_OCCURENCE);

		mockEndpoint.expect().get().withPath("/greeting").andReturn(200, "Hello from A").once();
		String response = restTemplate.getForObject("http://testapp/greeting", String.class);
		Assert.assertEquals("Hello from A",response);
		LOG.info(">>>>>>>>>> END PART 1 <<<<<<<<<<<<<");

		LOG.info(">>>>>>>>>> BEGIN PART 2 <<<<<<<<<<<<<");
		try {
			Thread.sleep(2000);
		    restTemplate.getForObject("http://testapp/greeting", String.class);
		    fail("My method didn't throw when I expected it to");
		} catch (Exception e) {
			// No endpoint is available anymore and Ribbon list is empty
			Assert.assertEquals("No instances available for testapp",e.getMessage());
		}
		LOG.info(">>>>>>>>>> END PART 2 <<<<<<<<<<<<<");

		LOG.info(">>>>>>>>>> BEGIN PART 3 <<<<<<<<<<<<<");
		mockServer.expect().get()
			.withPath("/api/v1/namespaces/testns/endpoints/testapp")
			.andReturn(200, newEndpoint("testapp-a","testns", mockEndpoint)).always();

		try {
			Thread.sleep(2000);
		} catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		mockEndpoint.expect().get().withPath("/greeting").andReturn(200, "Hello from A").once();
		response = restTemplate.getForObject("http://testapp/greeting", String.class);
		Assert.assertEquals("Hello from A",response);
		LOG.info(">>>>>>>>>> END PART 3 <<<<<<<<<<<<<");
	}

	public static Endpoints newEndpoint(String name, String namespace, DefaultMockServer mockServer) {
		return new EndpointsBuilder()
			        .withNewMetadata()
			          .withName(name)
			          .withNamespace(namespace)
			          .endMetadata()
			        .addNewSubset()
			          .addNewAddress().withIp(mockServer.getHostName()).endAddress()
			          .addNewPort("http",mockServer.getPort(),"http")
			        .endSubset()
			        .build();
	}

}

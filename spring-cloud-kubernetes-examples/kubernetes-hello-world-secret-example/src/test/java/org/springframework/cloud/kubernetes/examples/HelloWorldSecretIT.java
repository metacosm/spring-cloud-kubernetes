package org.springframework.cloud.kubernetes.examples;


import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.arquillian.cube.kubernetes.annotations.PortForward;
import org.arquillian.cube.kubernetes.impl.requirement.RequiresKubernetes;
import org.arquillian.cube.requirement.ArquillianConditionalRunner;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Named;
import java.io.IOException;
import java.net.URL;

@RunWith(ArquillianConditionalRunner.class)
@RequiresKubernetes
public class HelloWorldSecretIT {

	@ArquillianResource
	@Named("kubernetes-hello-world-s") //The service name is "${project.artifactId}".substring(0,23)
	@PortForward
	URL url;

	@Test
	public void service_should_be_accessible() throws IOException {
		OkHttpClient client = new OkHttpClient();
		Request request = new Request.Builder().get().url(url).build();
		Response response = client.newCall(request).execute();
		Assert.assertTrue(response.isSuccessful());
		response.close();
	}
}

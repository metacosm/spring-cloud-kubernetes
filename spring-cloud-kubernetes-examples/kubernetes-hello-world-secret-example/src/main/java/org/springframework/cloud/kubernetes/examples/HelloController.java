package org.springframework.cloud.kubernetes.examples;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
	@Autowired
	private GreetingProperties properties;

	@RequestMapping("/")
	public String hello() {
		return "Hello " + properties.getSecretName();
	}

}

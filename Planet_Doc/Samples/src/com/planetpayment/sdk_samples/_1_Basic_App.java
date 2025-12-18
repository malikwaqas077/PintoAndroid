package com.planetpayment.sdk_samples;

import integrate_clientsdk.About;

public class _1_Basic_App {

	public static void main(String[] args) {

		// Get the SDK version
		String szVersion = About.releaseVersion();
		System.out.println("SDK Version: " + szVersion);
	}

}

// In this sample we connect to the terminal and activate all three callbacks.
// You should see the channel status handler (i.e. comms) being called.
// You won�t see the response callback called because we haven�t done a transaction yet.
// 
// The console output should look like this:
// Create channel and datalink
// Create CommunicationContext
// Create the integra object
// Set the Status handler
// 
// Received a channel event of type: NONE
// statusMessage: None
// statusCode: 0
// 
// Received a channel event of type: CONNECTED
// statusMessage: Connected
// statusCode: 1

package com.planetpayment.sdk_samples;

import java.util.Map;
import java.util.Scanner;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;

public class _2_2_Callbacks {

	public static void main(String[] args) {

		// Set the terminal IP, port and timeout. Note if the terminal cannot connect it
		// will just timeout after 30 seconds.
		String szTerminalIP = "192.168.0.13"; // Set the terminal IP.
		int nTerminalPort = 1234; // Set the terminal port.
		int nTimeout = 30; // Timeout in seconds.

		// Create channel and datalink. The terminal must have this channel and datalink
		// available.
		System.out.println("Create channel and datalink");
		IChannel myChannel = new ChannelSocketClient(szTerminalIP, nTerminalPort, nTimeout);
		IDatalink myDatalink = new Datalink();

		System.out.println("Create CommunicationContext");
		CommunicationContext myContext = new CommunicationContext(myChannel, myDatalink);

		// Create the integra object
		System.out.println("Create the integra object");
		Integra myIntegra = new Integra(myContext);

		// Attaches or overrides the current terminal status listener
		System.out.println("Set the Status handler");
		myIntegra.setStatusUpdateHandler(statusUpdate -> {

			System.out.println("Received a status update:");
			Map<String, String> details = statusUpdate.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {
					System.out.println(entry.getKey() + ": " + entry.getValue());

				}
			}
		});

		// Attaches or overrides the current terminal response message listener
		myIntegra.setResponseHandler(response -> {

			System.out.println("Received a response");
			Map<String, String> details = response.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {
					System.out.println(entry.getKey() + ": " + entry.getValue());

				}
			}
		});

		// Attaches or overrides the current terminal connection status listener
		myIntegra.setChannelStatusListener(channelEvent -> {

			System.out.println("Received a channel event of type: " + channelEvent.getType());
			Map<String, String> details = channelEvent.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {
					System.out.println(entry.getKey() + ": " + entry.getValue());

				}
			}
		});

		// Keep the integra context alive otherwise we won�t see the status updates.
		System.out.println("Press any button on the terminal to see the status updates.");
		System.out.println("Press return key to close the app.");
		Scanner keyboard = new Scanner(System.in);
		keyboard.nextLine();
		keyboard.close();
	}

}

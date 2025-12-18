// In this sample we connect to the terminal, activate all three callbacks and send a CheckStatusRequest.
// You should see the channel response handler being called with the response to the request.
//
// The console output should contain the following (as well as the other messages):
// Received a response
// Status: 1
// Type: CheckStatus
// Message: 
// EmvTerminalId: 
// RequesterTransRefNum: 00001-00001
// SequenceNumber: 0
// Result: A

package com.planetpayment.sdk_samples;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Error.ErrorType;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelEvent.ChannelEventType;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.request.CheckStatusRequest;
import integrate_clientsdk.request.IRequest;

public class _2_3_Callbacks {

	public static boolean bConnected;

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
		bConnected = false;
		System.out.println("Connecting to to IP: " + szTerminalIP + " port: " + nTerminalPort);
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

			if (channelEvent.getType() == ChannelEventType.CONNECTED) {
				bConnected = true;
			}

			System.out.println("Received a channel event of type: " + channelEvent.getType());
			Map<String, String> details = channelEvent.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {
					System.out.println(entry.getKey() + ": " + entry.getValue());

				}
			}
		});

		// Wait until we are connected before sending the request.
		while (true) {
			// System.out.println("bConnected: " + bConnected);
			if (myContext.isConnected() == true)
				break;

			if (bConnected == true) // this does not seem to work
				break;
		}
		System.out.println("Connected");

		// Create a check status request
		Map<String, String> myOptions = new HashMap<String, String>();
		myOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, "00001-00001");
		IRequest myRequest = new CheckStatusRequest(myOptions);

		// Send the request
		AtomicInteger mySequenceNumber = new AtomicInteger();
		System.out.println("sendRequest");
		ErrorType error = myIntegra.sendRequest(myRequest, mySequenceNumber);
		System.out.println("sendRequest nSequenceNumber: " + mySequenceNumber + ", response:" + error.toString());

		// Keep the integra context alive otherwise we won�t see the status updates.
		System.out.println("Press any button on the terminal to see the status updates.");
		System.out.println("Press return key to close the app.");
		Scanner keyboard = new Scanner(System.in);
		keyboard.nextLine();
		keyboard.close();
	}

}

// In this sample we demonstrate the ChannelFactory functions in the following order:
// Use getChannelList() to check what channels are available.
// Use getOptionsForChannel() to check what parameters are needed for a particular channel.
// Use channelOptions.put() to set channel parameters like channel type, IP, port and timeout.
// Use validateOptions() to check that all the parameters are setup correctly.
// Also demonstrates functions getChannelType and getChannelName.
// Finally if all is good we use getChannel() to get the channel.
// 
// The console output should contain the following (as well as the other messages):
// Channel list: ChannelComPort, ChannelSocketClient, ChannelSocketClientSSL, 
// Parameters required for channel type 'ChannelSocketClient': host, ip, port, timeout, 
// Create channel: ChannelSocketClient
// ChannelType: CHANNEL_CLIENT_SOCKET
// ChannelType: CHANNEL_CLIENT_SOCKET
// ChannelName: ChannelSocketClient
// Create CommunicationContext
// Connecting to to IP: 192.168.0.13 port: 1234
//
// To simulate a missing channel parameter comment out one of the "channelOptions.put()" lines.
// The console output should contain the following (as well as the other messages):
// Channel list: ChannelComPort, ChannelSocketClient, ChannelSocketClientSSL, 
// Parameters required for channel type 'ChannelSocketClient': host, ip, port, timeout, 
// Create channel: ChannelSocketClient
// ChannelType: CHANNEL_CLIENT_SOCKET
// ChannelType: CHANNEL_CLIENT_SOCKET
// ChannelName: ChannelSocketClient
// ERROR. ChannelFactory missing parameter: MISSING_PARAM_HOST_OR_IP

package com.planetpayment.sdk_samples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Error.ErrorType;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelEvent;
import integrate_clientsdk.channel.ChannelEvent.ChannelEventType;
import integrate_clientsdk.channel.ChannelFactory;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.channel.IChannelStatusListener;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.logger.Logger;
import integrate_clientsdk.request.IRequest;
import integrate_clientsdk.request.settlement.SaleRequest;
import integrate_clientsdk.response.IResponseHandler;
import integrate_clientsdk.response.IStatusUpdateHandler;
import integrate_clientsdk.response.Response;
import integrate_clientsdk.response.StatusUpdate;

public class _4_3_ChannelFactory {

	public static boolean bConnected;
	public static boolean bTransactionCompleted;
	public static boolean bTerminalReady;

	// Create the status handler
	static class StatusHandler implements IStatusUpdateHandler {
		@Override
		public void onStatusUpdate(StatusUpdate statusUpdate) {
			Map<String, String> details = statusUpdate.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {

					if (entry.getKey().contentEquals("StatusMessage")) {
						System.out.println(entry.getKey() + ": " + entry.getValue());
					}

					if (entry.getKey().contentEquals("StatusMessage") && entry.getValue().contentEquals("Terminal ready")) {
						bTerminalReady = true;
					}
				}
			}
		}

	}

	// Create the response handler. This will be called a the end of a transaction.
	static class ResponseHandler implements IResponseHandler {
		@Override
		public void onResponse(Response response) {
			Map<String, String> details = response.getOptions();
			if (details != null) {

				for (Map.Entry<String, String> entry : details.entrySet()) {
					if (entry.getKey().contentEquals("Result") || entry.getKey().contentEquals("BankResultCode") || entry.getKey().contentEquals("Message") || entry.getKey().contentEquals("RequesterTransRefNum")) {
						System.out.println("Transaction Response:: " + entry.getKey() + ": " + entry.getValue());
						bTransactionCompleted = true;
					}

				}
			}
		}

	}

	// Create the channel status handler. This will be called with comms events, i.e. when the terminal is connected.
	static class ChannelStatusListener implements IChannelStatusListener {
		@Override
		public void onChannelEvent(ChannelEvent channelEvent) {
			if (channelEvent.getType() == ChannelEventType.CONNECTED) {
				System.out.println("ChannelEventType::CONNECTED");
				bConnected = true;
			}
		}

	}

	public static void main(String[] args) {

		// Setup basic logger to a file only
		Logger.setLoggerSetting(Logger.LoggerSettings.LOGGING_FILE_BASE_NAME, "Logfile_%Y%m%d.txt"); // if you want a different filename you should call this first.
		Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL, "trace"); // Valid values are trace, debug, info, warn, error, fatal, none.
		Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS, "file"); // Valid values are console, file, graylog, json, server.
		Logger.initialize(null); // The default name is log.properties but any filename can be used.

		// Set the terminal IP, port and timeout. Note if the terminal cannot connect it will just timeout after 30 seconds.
		String szTerminalIP = "192.168.0.13"; // Set the terminal IP.
		String szTerminalPort = "1234"; // Set the terminal port. 
		String szTimeout = "5"; // Timeout in seconds.

		// Check what channels are available
		List<String> channelList = ChannelFactory.getChannelList();
		System.out.print("Channel list: ");
		for (int i = 0; i < channelList.size(); i++) {

			System.out.print(channelList.get(i) + ", ");
		}
		System.out.println("");

		// Check what options are required
		List<String> getOptions = ChannelFactory.getOptionsForChannel(ChannelSocketClient.CHANNEL_TYPE_VALUE);
		System.out.print("Parameters required for channel type '" + ChannelSocketClient.CHANNEL_TYPE_VALUE + "': ");
		for (int i = 0; i < getOptions.size(); i++) {

			System.out.print(getOptions.get(i) + ", ");
		}
		System.out.println("");

		// Create channel using ChannelFactory.
		Map<String, String> channelOptions = new HashMap<String, String>();
		System.out.println("Create channel: " + ChannelSocketClient.CHANNEL_TYPE_VALUE);
		channelOptions.put(ChannelFactory.KEY_CHANNEL, ChannelSocketClient.CHANNEL_TYPE_VALUE);
		channelOptions.put(ChannelFactory.KEY_HOST, szTerminalIP);
		channelOptions.put(ChannelFactory.KEY_PORT, szTerminalPort);
		channelOptions.put(ChannelFactory.KEY_TIMEOUT, szTimeout);

		// Get channel type. 
		ChannelFactory.ChannelType myChannelType = ChannelFactory.getChannelType(channelOptions);
		System.out.println("ChannelType: " + myChannelType);
		System.out.println("ChannelType: " + ChannelFactory.getChannelType(ChannelSocketClient.CHANNEL_TYPE_VALUE));
		// Get channel type, returns a string.
		System.out.println("ChannelName: " + ChannelFactory.getChannelName(myChannelType));

		// Validate the channel options
		ErrorType errorChannel = ChannelFactory.validateOptions(channelOptions);
		if (errorChannel != ErrorType.SUCCESS) {

			System.out.println("ERROR. ChannelFactory missing parameter: " + errorChannel.toString());
		} else {

			// Create the channel.
			IChannel myChannel = ChannelFactory.getChannel(channelOptions);

			// Create the datalink.
			IDatalink myDatalink = new Datalink();

			System.out.println("Create CommunicationContext");
			CommunicationContext myContext = new CommunicationContext(myChannel, myDatalink);

			// Create the integra object
			bConnected = false;
			System.out.println("Connecting to to IP: " + szTerminalIP + " port: " + szTerminalPort);
			Integra myIntegra = new Integra(myContext);

			// Attaches or overrides the current terminal status listener
			StatusHandler myStatusHandler = new StatusHandler();
			myIntegra.setStatusUpdateHandler(myStatusHandler);

			// Attaches or overrides the current terminal response message listener
			ResponseHandler myResponseHandler = new ResponseHandler();
			myIntegra.setResponseHandler(myResponseHandler);

			// Attaches or overrides the current terminal connection status listener
			ChannelStatusListener myChannelStatusListener = new ChannelStatusListener();
			myIntegra.setChannelStatusListener(myChannelStatusListener);

			// Wait until we are connected before sending the request.
			while (bConnected == false) {

				sleep(10);
			}

			System.out.println("Connected");

			// Create a request
			Map<String, String> myOptions = new HashMap<String, String>();
			myOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, "00001-00001"); // mandatory parameter
			myOptions.put(IRequest.TAG_AMOUNT, "10.00"); // mandatory parameter
			IRequest myRequest = new SaleRequest(myOptions);

			// Validate options before sending the request
			if (myRequest.validateOptions() == false) { // Returns true if all parameter options are set

				System.out.println("ERROR. Request missing some parameters.");
			} else {

				// Send the request
				AtomicInteger mySequenceNumber = new AtomicInteger(); // This is an output variable. It will start at 0 and increment for every transaction.
				bTransactionCompleted = false;
				bTerminalReady = false;
				System.out.println("sendRequest");
				ErrorType error = myIntegra.sendRequest(myRequest, mySequenceNumber);
				System.out.println("sendRequest nSequenceNumber: " + mySequenceNumber + ", response:" + error.toString());

				// wait till we get a response
				while (bTransactionCompleted == false) {

					sleep(10);
				}
				System.out.println("Transaction Completed");

				// wait till the terminal is ready again
				while (bTerminalReady == false) {

					sleep(10);
				}
				System.out.println("Terminal Ready");

			}
			// Keep the integra context alive otherwise we won�t see the status updates.
			System.out.println("Enter a key to quit:");
			Scanner keyboard = new Scanner(System.in);
			keyboard.nextLine();
			keyboard.close();
		}
	}

	static private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
}

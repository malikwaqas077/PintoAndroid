// Create a simple SaleRequest and send it.
// Wait until we are connected before sending the request.
// Add the options (i.e. reference number and transaction amount) using requestOptions.insert().
// Create the sale request by passing these options to a SaleRequest().
// Use validateOptions() to validate the options are setup correctly.
// Finally send the request using sendRequest() and check the response using requestError.
//
// The console output should contain the following (as well as the other messages):
// Add request options.
// Create request.
// Validate request.
// sendRequest
// sendRequest nSequenceNumber: 0

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
import integrate_clientsdk.datalink.DatalinkFactory;
import integrate_clientsdk.datalink.DatalinkStxEtxCrcSendAckSeqCounter;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.logger.Logger;
import integrate_clientsdk.request.IRequest;
import integrate_clientsdk.request.settlement.SaleRequest;
import integrate_clientsdk.response.IResponseHandler;
import integrate_clientsdk.response.IStatusUpdateHandler;
import integrate_clientsdk.response.Response;
import integrate_clientsdk.response.StatusUpdate;

public class _6_1_Requests {

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

			// Retrieves a list of all supported datalinks
			List<String> myDatalinkList = DatalinkFactory.getDatalinkList();
			System.out.print("Supported datalinks: ");
			for (int i = 0; i < myDatalinkList.size(); i++) {

				System.out.print(myDatalinkList.get(i) + ", ");
			}
			System.out.println("");

			// Check what options are required
			List<String> myDatalinkOptions = DatalinkFactory.getOptionsForDatalink(DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE);
			System.out.print("Datalink options for " + DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE + ": ");
			for (int i = 0; i < myDatalinkOptions.size(); i++) {

				System.out.print(myDatalinkOptions.get(i) + ", ");
			}
			System.out.println("");

			// Create datalink using DatalinkFactory. 
			System.out.println("Create datalink using DatalinkFactory : " + DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE);
			Map<String, String> datalinkOptions = new HashMap<String, String>();
			datalinkOptions.put(DatalinkFactory.KEY_DATALINK, DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE);
			datalinkOptions.put(DatalinkFactory.KEY_ACK_TIMEOUT, "30000"); // The maximum ACK timeout for the datalink, in milliseconds. 7000 by default
			datalinkOptions.put(DatalinkFactory.KEY_ACK_MAX_RETRIES, "2"); // The maximum ACK resend attempts by the Datalink. 3 by default
			datalinkOptions.put(DatalinkFactory.KEY_KEEP_ALIVE_INTERVAL, "1"); // Sets the interval between keep alive packets, 0 by default
			datalinkOptions.put(DatalinkFactory.KEY_DUPLICATE_CHECK, "true"); // Enables the verification for duplicate messages, Enabled by default
			datalinkOptions.put(DatalinkFactory.KEY_MASK_NON_ASCII, "false"); // Enables masking non-ascii chars, default unknown.
			datalinkOptions.put(DatalinkFactory.KEY_SYN_BYTES, "0"); // Sets the amount of SYN bytes sent with each message, Defaults to 0

			// Validate the datalink options. This will only return error if KEY_DATALINK is missing.
			ErrorType errorDatalink = DatalinkFactory.validateOptions(datalinkOptions);
			if (errorDatalink != ErrorType.SUCCESS) {

				System.out.println("ERROR. ChannelFactory missing parameter: " + errorDatalink.toString());
			} else {

				// Get datalink type, both functions return an enum.   
				DatalinkFactory.DatalinkType myDatalinkType = DatalinkFactory.getDatalinkType(datalinkOptions);
				System.out.println("DatalinkType: " + myDatalinkType);
				System.out.println("DatalinkType: " + DatalinkFactory.getDatalinkType(DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE));
				// Get datalink type, returns a string.
				System.out.println("DatalinkName: " + DatalinkFactory.getDatalinkName(myDatalinkType));

				IDatalink myDatalink = DatalinkFactory.getDatalink(datalinkOptions);

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
				System.out.println("Add request options.");
				Map<String, String> requestOptions = new HashMap<String, String>();
				requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, "00001-00001"); // mandatory parameter
				requestOptions.put(IRequest.TAG_AMOUNT, "10.00"); // mandatory parameter
				System.out.println("Create request.");
				IRequest myRequest = new SaleRequest(requestOptions);

				// Validate options before sending the request
				System.out.println("Validate request.");
				if (myRequest.validateOptions() == false) { // Returns true if all parameter options are set

					System.out.println("ERROR. Request missing some parameters.");
				} else {

					// Send the request
					AtomicInteger mySequenceNumber = new AtomicInteger(); // This is an output variable. It will start at 0 and increment for every transaction.
					bTransactionCompleted = false;
					bTerminalReady = false;
					System.out.println("sendRequest");
					ErrorType requestError = myIntegra.sendRequest(myRequest, mySequenceNumber);
					if (requestError != ErrorType.SUCCESS) {
						System.out.println("Error sending request:" + requestError.toString());
					} else {
						System.out.println("sendRequest nSequenceNumber: " + mySequenceNumber);

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

				}
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

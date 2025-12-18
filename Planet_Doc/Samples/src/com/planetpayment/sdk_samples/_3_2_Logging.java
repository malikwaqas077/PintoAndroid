// In this sample you will be prompted to select a Sale or Refund transaction. 
// We have set trace (full) logs to log to a file but the console will only show a minimum of messages.
// Multiple logfiles will be created like this: Logfile_20220907.txt.0, Logfile_20220907.txt.1, Logfile_20220907.txt.2
// 
// The console output should contain the following (as well as the other messages):
// LOG_LEVEL:                     TRACE
// LOG_LEVEL_AND_APPENDERS:       file
// PATTERN:                       %d %t [%c{1}] %p - %m
// LOGGING_FILE_BASE_NAME:        Logfile_%Y%m%d.txt
// FILESIZE:                      25
// FILE_MAX_NUMBER:               10
// SERVER_LOG_PORT:               10000
// SERVER_LOG_STORE:              false
// SERVER_LOG_STORE_MAX_MESSAGES: 1000
// UNIQUE_ID:                     ZBDO14YVLLDS
// Create channel and datalink
// Create CommunicationContext
// Connecting to to IP: 192.168.0.13 port: 1234
// ChannelEventType::CONNECTED
// Connected
// Enter S to send a sale:
// Enter R to send a refund:
// S
// sendRequest
// sendRequest nSequenceNumber: 0, response:SUCCESS
// StatusMessage: Insert or swipe card
// StatusMessage: Insert, swipe or present card
// StatusMessage: Transaction cancelled
// Received a response
// Transaction Response:: Result: R
// Transaction Response:: RequesterTransRefNum: 1
// Transaction Completed
// StatusMessage: Terminal ready
// Terminal Ready

package com.planetpayment.sdk_samples;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Error.ErrorType;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelEvent;
import integrate_clientsdk.channel.ChannelEvent.ChannelEventType;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.channel.IChannelStatusListener;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.logger.Logger;
import integrate_clientsdk.request.IRequest;
import integrate_clientsdk.request.RequestFactory;
import integrate_clientsdk.response.IResponseHandler;
import integrate_clientsdk.response.IStatusUpdateHandler;
import integrate_clientsdk.response.Response;
import integrate_clientsdk.response.StatusUpdate;

public class _3_2_Logging {

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
			System.out.println("Received a response");
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
		Logger.setLoggerSetting(Logger.LoggerSettings.FILESIZE, "25");
		Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS, "file"); // Valid values are console, file, graylog, json, server.
		Logger.initialize(null); // The default name is log.properties but any filename can be used.

		// Get logger data
		System.out.println("LOG_LEVEL:                     " + Logger.getLoggerSetting(Logger.LoggerSettings.LOG_LEVEL));
		System.out.println("LOG_LEVEL_AND_APPENDERS:       " + Logger.getLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS));
		System.out.println("PATTERN:                       " + Logger.getLoggerSetting(Logger.LoggerSettings.PATTERN));
		System.out.println("LOGGING_FILE_BASE_NAME:        " + Logger.getLoggerSetting(Logger.LoggerSettings.LOGGING_FILE_BASE_NAME));
		System.out.println("FILESIZE:                      " + Logger.getLoggerSetting(Logger.LoggerSettings.FILESIZE));
		System.out.println("FILE_MAX_NUMBER:               " + Logger.getLoggerSetting(Logger.LoggerSettings.FILE_MAX_NUMBER));
		System.out.println("SERVER_LOG_PORT:               " + Logger.getLoggerSetting(Logger.LoggerSettings.SERVER_LOG_PORT));
		System.out.println("SERVER_LOG_STORE:              " + Logger.getLoggerSetting(Logger.LoggerSettings.SERVER_LOG_STORE));
		System.out.println("SERVER_LOG_STORE_MAX_MESSAGES: " + Logger.getLoggerSetting(Logger.LoggerSettings.SERVER_LOG_STORE_MAX_MESSAGES));
		System.out.println("UNIQUE_ID:                     " + Logger.getLoggerSetting(Logger.LoggerSettings.UNIQUE_ID));

		// Set the terminal IP, port and timeout. Note if the terminal cannot connect it will just timeout after 30 seconds.
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

		// Create a request using the Request Factory
		int nTransactionReferenceNumber = 0;

		// Start a transaction
		System.out.println("Enter S to send a sale:");
		System.out.println("Enter R to send a refund:");

		Scanner keyboard = new Scanner(System.in);
		String input = keyboard.nextLine();

		while (true) {
			// create the request, default is sale.
			Map<String, String> requestOptions = new HashMap<String, String>();
			if (input.contains("R")) {
				requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.REFUND_REQUEST));
			} else {
				requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.SALE_REQUEST));
			}
			nTransactionReferenceNumber++; // increment the reference number
			requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, String.valueOf(nTransactionReferenceNumber)); // mandatory parameter
			requestOptions.put(IRequest.TAG_AMOUNT, "10.00"); // mandatory parameter
			IRequest myRequest = RequestFactory.getRequest(requestOptions);

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

				// Start a transaction
				System.out.println("");
				System.out.println("Enter S to send a sale:");
				System.out.println("Enter R to send a refund:");
				System.out.println("Enter Q to quit:");
				input = keyboard.nextLine();
				if (input.contains("Q")) {
					break;
				}
			}
		}
		keyboard.close();
		System.out.println("Application exiting");
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

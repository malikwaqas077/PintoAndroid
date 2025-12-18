// In this sample we setup a basic logger.
// 
// The log level is "trace" so you will see logs for everything: trace, debug, info, warn, error and fatal.
// If you set the log level to debug then you will see logs for: debug, info, warn, error and fatal.
// 
// The appenders are set to "console, file" meaning logs will be sent to both the console and a file.
// 
// A log.properties file will be created in then project directory along with logfiles.
// 
// The console output should contain the following (as well as the other messages):
// 2022-09-13 10:52:19.713 Datalink3[Datalink:552] TRACE - propagateDatalinkEvent DatalinkEvent.CONNECTED
// 2022-09-13 10:52:19.713 Datalink3[Datalink:553] TRACE - propagateDatalinkEvent() for 1 listeners
// 2022-09-13 10:52:19.714 Datalink3[DatalinkController:309] DEBUG - DatalinkEvent DatalinkEvent.CONNECTED from Channel 0
// 2022-09-13 10:52:19.715 Datalink3[DatalinkController:355] DEBUG - propagateDatalinkEvent DatalinkEvent.CONNECTED
// 2022-09-13 10:52:19.716 Datalink3[DatalinkController:359] TRACE - propagating Data ""
// 2022-09-13 10:52:19.717 Datalink3[CommunicationContext] TRACE - Event received: 1:

package com.planetpayment.sdk_samples;

import java.util.Scanner;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.logger.Logger;

public class _3_1_Logging {

	public static boolean bConnected;

	public static void main(String[] args) {

		// Setup basic logger to both console and file.
		Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL, "trace"); // Valid values are trace, debug, info, warn, error, fatal, none.
		Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS, "console, file"); // Valid values are console, file, graylog, json, server.
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

		// Keep the integra context alive otherwise we won�t see the status updates.
		System.out.println("Press any button on the terminal to see the status updates.");
		System.out.println("Press return key to close the app.");
		Scanner keyboard = new Scanner(System.in);
		keyboard.nextLine();
		keyboard.close();
		myIntegra.dispose();
	}

}

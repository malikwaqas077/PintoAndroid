// In this sample we connect to the terminal and activate the status update callback.
// Then if you press any button on the terminal, i.e by changing the menus or selecting a sale you should see the status messages returned to via the console.
// 
// The console output should look like this:
// Received a status update:
// RequesterLocationId: 604000
// Type: EftTerminalStatus
// EmvTerminalRefManufacturer: 00000535
// StatusType: D
// EmvTerminalId: POS1 
// RequesterTransRefNum: 
// SequenceNumber: 
// StatusCode: 1049
// StatusMessage: Sale, Refund, PreAuth, Transaction Inquiry, Shift, Print, Gift card

package com.planetpayment.sdk_samples;

import java.util.Map;
import java.util.Scanner;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Integra;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;

public class _2_1_Callbacks {

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

		// Keep the integra context alive otherwise we won�t see the status updates.
		System.out.println("Press any button on the terminal to see the status updates.");
		System.out.println("Press return key to close the app.");
		Scanner keyboard = new Scanner(System.in);
		keyboard.nextLine();
		keyboard.close();
	}

}

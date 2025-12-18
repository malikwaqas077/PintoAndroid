// Sample code for Data Request.
// A menu is presented with the options: Sale, Refund or Data request.
//
// The request "EftData" is part of the "Other" group and it has mandatory parameter "RequesterTransRefNum".
//
// The console output should contain the following (as well as the other messages):
//
// Group: Other contains requests: Cancel, CheckStatus, EftData,
// Parameters required for request type 'EftData': RequesterTransRefNum,
//
// This is a partial log of a sale transaction.
// EftSettlementType: Sale-Terminal
// TransRefNum: 3990
// TimeStamp: 20230516161759
// AmountUsed: 10.00
// RequesterTransRefNum: 00001-00001
// BankAuthCode: 168119
//
// This is a response from a Data request. Notice the details are the same as the original sale transaction.
// Received a response
// CardNumber: 420503xxxxxx0002
// InstallmentData.Plan:
// ResultReason: 00
// Message: Approval
// InstallmentData.NbMonths:
// Token: 6049743134991132424
// EftType: SettleSale
// MerchantId: 999020021
// CardSchemeName: VISA
// InstallmentData.ReasonInd:
// Result: A
// RequesterLocationId: 001622
// LoyaltyData.ReasonInd:
// BankResultCode: 00
// CardFunctionId: CC
// LoyaltyData.LoyaltyType:
// CardInvoiceCompanyName: PLANET PAYMENT
// CardExpiryDate: 4912
// Currency: EUR
// SequenceNumber: 1
// TransRefNum: 3990
// Amount: 10.00
// CardFunctionName: Credit Card
// EmvTerminalId: 00002931
// CardSchemeId: VS
// CardInputMethod: S
// TimeStamp: 20230516161759
// InstallmentData.NbOffset:
// AmountUsed: 10.00
// CardInvoiceCompanyId: PJ
// Type: EftData
// LoyaltyData.Indicator:
// InstallmentData.Indicator:
// InstallmentData.InstallmentType:
// RequesterTransRefNum: 00001-00001
// CurrencyUsed: EUR
// BankAuthCode: 168119

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import integrate_clientsdk.About;
import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Integra;
import integrate_clientsdk.Error.ErrorType;
import integrate_clientsdk.channel.ChannelEvent;
import integrate_clientsdk.channel.ChannelEvent.ChannelEventType;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.channel.IChannelStatusListener;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.datalink.IDatalink;
import integrate_clientsdk.request.RequestFactory;
import integrate_clientsdk.request.settlement.SaleRequest;
import integrate_clientsdk.request.DataRequest;
import integrate_clientsdk.request.TransactionInquiryRequest;
import integrate_clientsdk.request.IRequest;
import integrate_clientsdk.request.TagValues;
import integrate_clientsdk.response.IResponseHandler;
import integrate_clientsdk.response.IStatusUpdateHandler;
import integrate_clientsdk.response.Response;
import integrate_clientsdk.response.StatusUpdate;

public class _6_4_Requests {

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

                        if (entry.getValue().contentEquals("Terminal ready")) {
                            bTerminalReady = true;
                        }
                    }
                }
            }
        }
    }

    // Create the response handler. This will be called at the end of a transaction.
    static class ResponseHandler implements IResponseHandler {
        @Override
        public void onResponse(Response response) {
            Map<String, String> details = response.getOptions();
            if (details != null) {
                System.out.println("Received a response");
                for (Map.Entry<String, String> entry : details.entrySet()) {
                    System.out.println(entry.getKey() + ": " + entry.getValue());
                }
                System.out.println();

                for (Map.Entry<String, String> entry : details.entrySet()) {
                    if ((entry.getKey().contentEquals("Result")) || (entry.getKey().contentEquals("BankResultCode")) || (entry.getKey().contentEquals("Message")) || (entry.getKey().contentEquals("RequesterTransRefNum"))) {
                        System.out.println("Transaction Response: " + entry.getKey() + ": " + entry.getValue());
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


    // Add a function to get data from the console.
    static String getDataFromConsole(Scanner keyboard, String szTag)
    {
        System.out.println("Enter " + szTag + ":");
        return keyboard.nextLine();
    }

    public static void main(String[] args) {

        System.out.println("SDK Version: " + About.releaseVersion());

        // Set the terminal IP, port and timeout. Note if the terminal cannot connect it will just timeout after 30 seconds.
        String szTerminalIP = "192.168.0.19"; // Set the terminal IP.
        int nTerminalPort = 1235; // Set the terminal port.
        int nTimeout = 30; // Timeout in seconds.

        // Create channel and datalink. The terminal must have this channel and datalink available.
        System.out.println("Create channel and datalink");
        IChannel myChannel = new ChannelSocketClient(szTerminalIP, nTerminalPort, nTimeout);
        IDatalink myDatalink = new Datalink();

        System.out.println("Create CommunicationContext");
        CommunicationContext myContext = new CommunicationContext(myChannel, myDatalink);

        // Create the integra object
        bConnected = false;
        System.out.println("Connecting to IP: " + szTerminalIP + " port: " + nTerminalPort);
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

        // getRequestGroupList
        List<String> myRequestGroup = RequestFactory.getRequestGroupList();
        System.out.println("Request Group List: ");
        for (int i = 0; i < myRequestGroup.size(); i++) {

            List<String> myRequestList = RequestFactory.getRequestListForGroup(myRequestGroup.get(i));
            System.out.print("Group: " + myRequestGroup.get(i) + " contains requests: ");
            for (int j = 0; j < myRequestList.size(); j++) {

                System.out.print(myRequestList.get(j) + ", ");
            }
            System.out.println("");
        }

        // Check what options are required for a particular request
        List<String> getRequestOptions = RequestFactory.getOptionsForRequest(TransactionInquiryRequest.REQUEST_TYPE_VALUE);
        System.out.print("Parameters required for request type '" + TransactionInquiryRequest.REQUEST_TYPE_VALUE + "': ");
        for (int i = 0; i < getRequestOptions.size(); i++) {

            System.out.print(getRequestOptions.get(i) + ", ");
        }
        System.out.println("");

        // Create a unique reference number that will be used for all transactions.
        String szRequesterTransRefNumber = "20231113-001";
        String szSequenceNumber = "1";

        // Start a transaction, this can be either sale, refund or transaction inquiry.
        System.out.println("Enter 1 to send a sale:");
        System.out.println("Enter 2 to send a refund:");
        System.out.println("Enter 3 to send a data request:");
        System.out.println("Enter 4 to send a status request:");
        System.out.println("Enter 5 to send a checkline status request:");
        System.out.println("Enter 6 to send a transaction inquiry:");
        Scanner keyboard = new Scanner(System.in);
        String szTransactionType = keyboard.nextLine();

        while (true) {

            // create the request
            Map<String, String> requestOptions = new HashMap<String, String>();
            if (szTransactionType.contains("1")) {
                // The KEY_REQUEST value is a text string, for example "Sale-Terminal", "Refund-Terminal", "EftTransactionInquiry", "EftData" etc.
                // We can get this value using "RequestFactory.getRequestName(RequestFactory.RequestType.SALE_REQUEST)" or "SaleRequest.REQUEST_TYPE_VALUE" or by just passing the text string "Sale-Terminal".
                //requestOptions.put(RequestFactory.KEY_REQUEST, "Sale-Terminal");
                requestOptions.put(RequestFactory.KEY_REQUEST, SaleRequest.REQUEST_TYPE_VALUE);
                requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, szRequesterTransRefNumber);
                //requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.SALE_REQUEST));
                requestOptions.put(IRequest.TAG_AMOUNT, "10.00");
                requestOptions.put(IRequest.TAG_SEQUENCENUMBER, szSequenceNumber);
                requestOptions.put(IRequest.TAG_REQUESTERLOCATIONID, "ESMA-L-063");
                requestOptions.put(IRequest.TAG_EMVSCENARIOID, "DT"); // Valid options "DT" or "DU"
                requestOptions.put(IRequest.TAG_EMVTERMINALID, "POS1");
                requestOptions.put(IRequest.TAG_APP_BEHAVIOR, TagValues.TAG_VALUE_INTEGRATOR); // Valid options are TAG_VALUE_BACKGROUND, TAG_VALUE_FOREGROUND, or TAG_VALUE_INTEGRATOR.
            }
            else if (szTransactionType.contains("2")) {
                requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.REFUND_REQUEST));
                requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, szRequesterTransRefNumber);
                requestOptions.put(IRequest.TAG_AMOUNT, "5.00");
            }
            else if (szTransactionType.contains("3")) {
                requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.DATA_REQUEST));
                requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, szRequesterTransRefNumber);
            }
            else if (szTransactionType.contains("4")) {
                requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.CHECK_STATUS_REQUEST));
                requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, szRequesterTransRefNumber);
            }
            else if (szTransactionType.contains("5")) {
                requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.CHECKLINE_STATUS_REQUEST));
                requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, szRequesterTransRefNumber);
            } else if (szTransactionType.contains("6")) {
                requestOptions.put(RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.TRANSACTION_INQUIRY_REQUEST));
                requestOptions.put(IRequest.TAG_SEQUENCENUMBER, szSequenceNumber);

                System.out.println("Transaction Inquiry:");
                System.out.println("Enter 1 to do Transaction Inquiry with EftType Last:");
                System.out.println("Enter 2 to do Transaction Inquiry with EftType SeqNum:");
                System.out.println("Enter 3 to do Transaction Inquiry with EftType Status or List:");
                szTransactionType = keyboard.nextLine();
                if (szTransactionType.contains("1")) {
                    requestOptions.put(IRequest.TAG_EFTTYPE, "Last");
                    requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, getDataFromConsole(keyboard, IRequest.TAG_REQUESTERTRANSREFNUM));
                }
                else if (szTransactionType.contains("2")) {
                    requestOptions.put(IRequest.TAG_EFTTYPE, "SeqNum");
                    requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, getDataFromConsole(keyboard, IRequest.TAG_REQUESTERTRANSREFNUM));
                    requestOptions.put(IRequest.TAG_ORIGINALSEQNUMBER, getDataFromConsole(keyboard, IRequest.TAG_ORIGINALSEQNUMBER));
                }
                else if (szTransactionType.contains("3")) {
                    System.out.println("Enter 1 to use EftType Status or any other key to use EftType List");
                    szTransactionType = keyboard.nextLine();
                    if (szTransactionType.contains("1")) {
                        requestOptions.put(IRequest.TAG_EFTTYPE, "Status");
                    }
                    else {
                        requestOptions.put(IRequest.TAG_EFTTYPE, "List");
                    }

                    System.out.println("Enter 1 using RequesterTransRefNum");
                    System.out.println("Enter 2 using OriginalPaymentReferenceId");
                    System.out.println("Enter 3 using OriginalPaymentReferenceId and OriginalRequestId");
                    szTransactionType = keyboard.nextLine();
                    if (szTransactionType.contains("1")) {
                        requestOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, getDataFromConsole(keyboard, IRequest.TAG_REQUESTERTRANSREFNUM));
                    }
                    else if (szTransactionType.contains("2")) {
                        requestOptions.put(IRequest.TAG_ORIGINALPAYMENTREFERENCEID, getDataFromConsole(keyboard, IRequest.TAG_ORIGINALPAYMENTREFERENCEID));
                    }
                    else if (szTransactionType.contains("3")) {
                        requestOptions.put(IRequest.TAG_ORIGINALPAYMENTREFERENCEID, getDataFromConsole(keyboard, IRequest.TAG_ORIGINALPAYMENTREFERENCEID));
                        requestOptions.put(IRequest.TAG_ORIGINALREQUESTID, getDataFromConsole(keyboard, IRequest.TAG_ORIGINALREQUESTID));
                    }
                }
                else
                {
                    System.out.println("Unknown option");
                }
            }
            else {
                System.out.println("Unknown option");
            }

            IRequest myRequest = RequestFactory.getRequest(requestOptions);

            // Validate options before sending the request
            if ((myRequest == null) || (myRequest.validateOptions() == false)) {

                System.out.println("ERROR. Request missing some parameters.");
            } else {

                // Send the request
                AtomicInteger mySequenceNumber = new AtomicInteger(); // This is an output variable. It will start at 0 and increment for every transaction.
                bTransactionCompleted = false;
                bTerminalReady = false;
                System.out.println("sendRequest");
                ErrorType error = myIntegra.sendRequest(myRequest, mySequenceNumber);
                System.out.println("sendRequest nSequenceNumber: " + mySequenceNumber + ", response:" + error.toString());

                // wait until we get a response
                while (bTransactionCompleted == false) {

                    sleep(10);
                }
                System.out.println("Transaction Completed");

                // wait until the terminal is ready again (only for sale and refund)
                if ((szTransactionType == "S") || (szTransactionType == "R"))
                {
                    while (bTerminalReady == false) {

                        sleep(10);
                    }
                }
                System.out.println("Terminal ready for next transaction");
            }

            // Start a transaction
            System.out.println("");
            System.out.println("Enter 1 to send a sale:");
            System.out.println("Enter 2 to send a refund:");
            System.out.println("Enter 3 to send a data request:");
            System.out.println("Enter 4 to send a status request:");
            System.out.println("Enter 5 to send a checkline status request:");
            System.out.println("Enter 6 to send a transaction inquiry:");
            System.out.println("Enter Q to quit:");
            szTransactionType = keyboard.nextLine();
            if (szTransactionType.contains("Q")) {
                break;
            }
        }

        System.out.println("Application finished.");
    }

    static private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Auto-generated catch block
            e.printStackTrace();
        }
    }
}

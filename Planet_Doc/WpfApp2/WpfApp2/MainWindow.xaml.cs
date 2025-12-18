using PlanetPaymentSDK;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace WpfApp2
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        public MainWindow()
        {
            InitializeComponent();
        }
        Integra myIntegra = null;

        private void Connect_Click(object sender, RoutedEventArgs e)
        {
            IChannel myChannel = new ChannelSocketClient("192.168.2.110", 1234, 30);
            IDatalink myDatalink = new DatalinkStxEtxCrcSendAckSeqCounter();

            CommunicationContext myContext = new CommunicationContext(myChannel, myDatalink);

            myIntegra = new Integra(myContext);
            myIntegra.statusUpdateHandler += statusHandler;
            myIntegra.responseUpdateHandler += responseHandler;
            myIntegra.channelStatusListener += channelStatusListener;


        }

        void statusHandler(StatusUpdate statusUpdate)
        {
            try
            {
                lvMessages.Dispatcher.BeginInvoke(DispatcherPriority.Normal,
                (Action)(() =>
                {
                    lvMessages.Items.Add("<============== StatusUpdate Received START ==============>");
                    foreach (KeyValuePair<string, string> entry in statusUpdate.getOptions())
                    {
                        if (entry.Key.ToLower().Contains("statusmessage"))
                            lblPaymentStatus.Content = entry.Value;
                        else if (entry.Key.ToLower().Contains("paymentreferenceid"))
                            tbTokenRef.Text = entry.Value;
                        else
                            lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }
                    lvMessages.Items.Add("<============== StatusUpdate Received END ==============>");
                }
                ));
            }
            catch (Exception excep)
            {

            }
        }

        void responseHandler(Response response)
        {
            try
            {
                lvMessages.Dispatcher.BeginInvoke(DispatcherPriority.Normal,
                (Action)(() =>
                {
                    lvMessages.Items.Add("<============== Response Received START ==============>");
                    foreach (KeyValuePair<string, string> entry in response.getOptions())
                    {
                        lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }

                    var options = response.getOptions();
                    options.TryGetValue("Result", out var result); // e.g. "A", "D", "C", etc.
                    options.TryGetValue("ResultReason", out var reason); // e.g. "00", "05", etc.
                    options.TryGetValue("Message", out var message); // e.g. "APPROVED", "DECLINED"

                    options.TryGetValue("RequesterTransRefNum", out var transactionNumber); //unique identifier for the transaction
                    options.TryGetValue("CardSchemeName", out var cardSchemeName); //card scheme eg "MASTERCARD", "VISA", etc.
                    options.TryGetValue("CardNumber", out var cardNumber); //card number
                    options.TryGetValue("BankAuthCode", out var bankAuthCode); //bank auth code
                    options.TryGetValue("EftSettlementType", out var EftSettlementType); //e.g. "Sale-Terminal", "Sale-Reversal"
                    options.TryGetValue("CardSchemeId", out var cardSchemeId); //card scheme id
                    options.TryGetValue("CardSchemeLabel", out var cardSchemeLabel); //card scheme label
                    options.TryGetValue("Token", out var cardToken); //card scheme label
                    options.TryGetValue("EmvApplicationId", out var emvApplicationId); //emv Application Id
                    options.TryGetValue("EmvCryptogram", out var emvCryptogram); //emv Cryptogram
                    options.TryGetValue("EmvCryptogramType", out var emvCryptogramType); // emv Cryptogram Type
                    options.TryGetValue("CardExpiryDate", out var cardExpiryDate); // card Expiry Date
                    options.TryGetValue("PrintData2", out var printData); // print data
                    options.TryGetValue("PaymentReferenceId", out var paymentReferenceId); // payment Reference Id
                    options.TryGetValue("ResultReason", out var resultReason); // payment Reference Id

                    // Normalize result code (uppercase)
                    result = result?.ToUpperInvariant();

                    switch (result)
                    {
                        case "A": //approved
                            tbTxnRef.Text = transactionNumber;
                            break;
                        default:
                            //HandleUnknown(options);
                            break;
                    }
                    lvMessages.Items.Add("<============== Response Received END ==============>");
                }
                ));
            }
            catch (Exception excep)
            {

            }
        }

        void channelStatusListener(ChannelEvent nEventType)
        {
            try
            {
                lvMessages.Dispatcher.BeginInvoke(DispatcherPriority.Normal,
                (Action)(() =>
                {
                    lvMessages.Items.Add("<============== ChannelEvent Received START ==============>");
                    foreach (KeyValuePair<string, string> entry in nEventType.getOptions())
                    {
                        lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }
                    lvMessages.Items.Add("<============== ChannelEvent Received END ==============>");
                }
                ));
            }
            catch (Exception excep)
            {

            }
        }

        private void btnStartPayment_Click(object sender, RoutedEventArgs e)
        {
            //convert from pence to decimal, and then to string
            decimal amount = 0;
            decimal.TryParse(tbAmount.Text, out amount);
            amount = amount / 100m;

            Dictionary<string, string> myOptions = new Dictionary<string, string>
            {
                { IRequest.TAG_AMOUNT, amount.ToString() },
                { IRequest.TAG_REQUESTERTRANSREFNUM, tbTxnRef.Text.ToString()}
            };

            int nSequenceNumber;
            IRequest myRequest = new SaleRequest(myOptions);
            ErrorType error = myIntegra.sendRequest(myRequest, out nSequenceNumber);
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            //convert from pence to decimal, and then to string
            decimal amount = 0;
            decimal.TryParse(tbAmount.Text, out amount);
            amount = amount / 100m;

            Dictionary<string, string> myOptions = new Dictionary<string, string>
            {
                { IRequest.TAG_AMOUNT, amount.ToString() },
                { IRequest.TAG_REQUESTERTRANSREFNUM, tbTxnRef.Text.ToString()}
            };

            int nSequenceNumber;
            IRequest myRequest = new PreAuthRequest(myOptions);
            ErrorType error = myIntegra.sendRequest(myRequest, out nSequenceNumber);
        }

        private void Button_Click_1(object sender, RoutedEventArgs e)
        {
            Dictionary<string, string> requestOptions = new Dictionary<string, string>
            {
                { RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.COMPLETION_REQUEST) },
                { IRequest.TAG_AMOUNT, "0.50" }
            };

            IRequest completionRequest = new CompletionRequest(requestOptions);

            //via RequestFactory
            //completionRequest = RequestFactory.getRequest(requestOptions);
            int nSequenceNumber = 0;

            ErrorType error = myIntegra.sendRequest(completionRequest, out nSequenceNumber);
        }
        private void Button_ClickReversal(object sender, RoutedEventArgs e)
        {
            lvMessages.Items.Add("Sale Refund for : " + tbTxnRef.Text);
            Dictionary<string, string> myOptions = new Dictionary<string, string>
                                    {
                                        { RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.SALE_REVERSAL_REQUEST) },
                                        { IRequest.TAG_REQUESTERTRANSREFNUM, tbTxnRef.Text },
                                        { IRequest.TAG_AMOUNT, tbAmount.Text.ToString() }
                                    };

            int nSequenceNumber;
            IRequest myRequest = new SaleReversalRequest(myOptions);
            ErrorType error = myIntegra.sendRequest(myRequest, out nSequenceNumber);
        }

        private void Button_ClickRefund(object sender, RoutedEventArgs e)
        {

            decimal amount = 0;
            decimal.TryParse(tbAmount.Text, out amount);
            amount = amount / 100m;
            lvMessages.Items.Add("Refund for the amount: " + tbAmount.Text);
            Dictionary<string, string> myOptions = new Dictionary<string, string>
                                    {
                                        { RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.REFUND_REQUEST) },
                                        { IRequest.TAG_AMOUNT, amount.ToString() },
                                        { IRequest.TAG_REQUESTERTRANSREFNUM, tbTxnRef.Text }
                                    };

            int nSequenceNumber;
            IRequest myRequest = new RefundRequest(myOptions);
            ErrorType error = myIntegra.sendRequest(myRequest, out nSequenceNumber);
        }

        private void Button_ClickInitialize(object sender, RoutedEventArgs e)
        {
            lvMessages.Items.Add("Initilise request");
            Dictionary<string, string> myOptions = new Dictionary<string, string>
                                    {
                                        { RequestFactory.KEY_REQUEST, RequestFactory.getRequestName(RequestFactory.RequestType.INITIALIZE_REQUEST) }
                                    };

            int nSequenceNumber;
            IRequest myRequest = new InitializeRequest(myOptions);
            ErrorType error = myIntegra.sendRequest(myRequest, out nSequenceNumber);
        }
    }
}

using PlanetPaymentSDK;
using System;
using System.Collections.Generic;
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
//using PlanetPaymentSDK;

namespace WpfApp1
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
                    lvMessages.Items.Add("StatusUpdate Received");
                    foreach (KeyValuePair<string, string> entry in statusUpdate.getOptions())
                    {
                        if (entry.Key.ToLower().Contains("statusmessage"))
                            lblPaymentStatus.Content = entry.Value;
                        else if (entry.Key.ToLower().Contains("paymentreferenceid"))
                            tbTokenRef.Text = entry.Value;
                        else
                            lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }
                }
                ));
            }
            catch(Exception excep)
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
                    lvMessages.Items.Add("Response Received");
                    foreach (KeyValuePair<string, string> entry in response.getOptions())
                    {
                        lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }
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
                    lvMessages.Items.Add("ChannelEvent Received");
                    foreach (KeyValuePair<string, string> entry in nEventType.getOptions())
                    {
                        lvMessages.Items.Add(entry.Key + " : " + entry.Value);
                    }
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
                { IRequest.TAG_AMOUNT, "0.50" },
                { IRequest., "0.50" }
            };

            IRequest completionRequest = new CompletionRequest(requestOptions);

            //via RequestFactory
            //completionRequest = RequestFactory.getRequest(requestOptions);
            int nSequenceNumber = 0;

            ErrorType error = myIntegra.sendRequest(completionRequest, out nSequenceNumber);
        }
    }


}

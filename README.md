## Reference application - OCI SDK for Java, Claim Check Pattern
This is a minimalistic Java application used to demonstrate the use of OCI SDK for Java. It shows how to implement the Claim Check Integration Pattern with OCI Object Storage and OCI Streaming. The application was written as a sample to my article in Java Magazine titled "First steps with Oracle Cloud Infrastructure SDK for Java".

### Architecture


1. A producer puts payload as an object to a bucket in OCI Object Storage and publishes message to a stream in OCI Streaming. The message stores the complete path to a newly created object.
2. Consumers who subscribe to the stream will receive the message and use the contained path to download a copy of the object.

### Preparation
This application interacts with Oracle Cloud Infrastructure Object Storage and Streaming services. It is assumed that you create a bucket and a [stream](https://docs.oracle.com/en-us/iaas/Content/Streaming/Tasks/managingstreams.htm#Managing_Streams) before executing the code.

Assuming your IDCS/IAM user is allowed to do so, you can create these resources using OCI CLI just like this:
```
oci streaming admin stream create --name claim-checks --partitions 1 --compartment-id $COMPARTMENT_OCID
oci os bucket create --name large-messages --compartment-id $COMPARTMENT_OCID
```
Now set the variables needed by your consumers and producer. Please replace the values with these relevant for you.
```
COMPARTMENT_OCID=...
STREAM_OCID=...
STREAM_ENDPOINT=https://cell-1.streaming.eu-frankfurt-1.oci.oraclecloud.com
BUCKET=large-messages
```

### Starting consumers
Let us start two consumers, each in separate terminal pane or window, using the same code base:
```
java -jar claim-check-client-1.0-jar-with-dependencies.jar consumer receiver-1 $COMPARTMENT_OCID $STREAM_OCID $STREAM_ENDPOINT
```
```
java -jar claim-check-client-1.0-jar-with-dependencies.jar consumer receiver-2 $COMPARTMENT_OCID $STREAM_OCID $STREAM_ENDPOINT
```
The consumers will listen and process incoming messages.

### Running a producer
To demonstrate the Claim-Check pattern in action, we need to create a mock of a large file first. In our scenario, the file will have 10MB and will imitate a PDF. Such a file cannot be processed as OCI Streaming message, because it is larger than 1MB. The producer will upload the file to Object Storage bucket first and pass the corresponding path to consumers as a message over OCI Streaming.

```bash
# Create a mock of a 10MB PDF
head -c $((10*1024*1024)) /dev/urandom > large.pdf
# Run a producer
java -jar claim-check-client-1.0-jar-with-dependencies.jar producer sender-1 $COMPARTMENT_OCID $STREAM_OCID $STREAM_ENDPOINT $BUCKET large.pdf
```
You should see something similar in the output:
```
producer sender-1: Your object storage namespace: yournamespace
producer sender-1: Found file: large.pdf (10240kB)
producer sender-1: Uploading the file as /n/yournamespace/b/large-messages/o/large.pdf
producer sender-1: Successfully uploaded the file
```

### Expected behaviour
In each individual terminal window where you started both consumers, there should be an entry stating that a message was received and a file has been downloaded
```
consumer receiver-1: Received a message pointing to: /n/yournamespace/b/large-messages/o/large.pdf
consumer receiver-1: Downloaded the file from object storage and saved it locally as: receiver-1-large.pdf
```
```
consumer receiver-2: Received a message pointing to: /n/yournamespace/b/large-messages/o/large.pdf
consumer receiver-2: Downloaded the file from object storage and saved it locally as: receiver-2-large.pdf
```
Feel free to compare all three files:
```
diff large.pdf receiver-1-large.pdf
diff large.pdf receiver-2-large.pdf
```
They should be identical.

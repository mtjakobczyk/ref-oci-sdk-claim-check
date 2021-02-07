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
### Starting consumers
Let us start two consumers, each in separate terminal pane or window, using the same code base:
```
java -jar ClaimCheckClient-1.0-jar-with-dependencies.jar consumer receiver-1 $COMPARTMENT_OCID $STREAM_OCID
```
```
java -jar ClaimCheckClient-1.0-jar-with-dependencies.jar consumer receiver-2 $COMPARTMENT_OCID $STREAM_OCID
```
The consumers will listen and process incoming messages.

### Running a producer
To demonstrate the Claim-Check pattern in action, we need to create a mock of a large file first. In our scenario, the file will have 10MB and will imitate a PDF. Such a file cannot be processed as OCI Streaming message, because it is larger than 1MB. The producer will upload the file to Object Storage bucket first and pass the corresponding path to consumers as a message over OCI Streaming.

```bash
# Create a mock of a 10MB PDF
head -c $((10*1024*1024)) /dev/urandom > large.pdf
# Run a producer
java -jar ClaimCheckClient-1.0-jar-with-dependencies.jar producer sender-1 $COMPARTMENT_OCID $STREAM_OCID $BUCKET large.pdf
```

### Expected behaviour
In each individual terminal window where you started both consumers, there should be an entry stating that a message was received and a file has been downloaded
```
receiver-1 received a message pointing to:
receiver-1 downloaded a file from object storage bucket:
```
```
receiver-2 received a message pointing to:
receiver-2 downloaded a file from object storage bucket:
```

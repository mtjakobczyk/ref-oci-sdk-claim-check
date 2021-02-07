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


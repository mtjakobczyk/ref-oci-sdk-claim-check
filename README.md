## Reference application - OCI SDK for Java, Claim Check Pattern
This is a minimalistic Java application used to demonstrate the use of OCI SDK for Java. It shows how to implement the Claim Check Integration Pattern with OCI Object Storage and OCI Streaming. The application was written as a sample to my article in Java Magazine titled "First steps with Oracle Cloud Infrastructure SDK for Java".

### Architecture


### Preparation
This application interacts with Oracle Cloud Infrastructure Object Storage and Streaming services. It is assumed that you create a bucket and a stream before executing the code.

Assuming your IDCS/IAM user is allowed to do so, you can create these resources using OCI CLI just like this:



Alternatively, 

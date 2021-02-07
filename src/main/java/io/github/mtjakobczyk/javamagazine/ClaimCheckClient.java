package io.github.mtjakobczyk.javamagazine;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;

public class ClaimCheckClient {
    // This is sample code. Goal is to present OCI SDK for Java. 
    // For brevity:
    // 1. error handling is nearly not present 
    // 2. intermediary POJOs are avoided
    // 3. everything is packed to the main method

    public static void main(String[] args) throws IOException {
        var clientType = args[0];
        var clientName = args[1];
        var logPrefix = clientType+" "+clientName+": ";
        var compartmentId = args[2];
        var streamId = args[3];
        var streamEndpoint = args[4];

        // Parse the .oci/config and use the authentication information from the DEFAULT profile  
        var configFile = ConfigFileReader.parseDefault();
        var ociAuthProvider = new ConfigFileAuthenticationDetailsProvider(configFile);

        // Prepare clients
        var osClient = ObjectStorageClient.builder().build(ociAuthProvider);
        var streamClient = StreamClient.builder()
                                .endpoint(streamEndpoint)
                                .build(ociAuthProvider);

        // Query for Object Storage namespace
        // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Namespace/GetNamespace
        var getNamespaceRequest = GetNamespaceRequest.builder()
                                    .compartmentId(compartmentId)
                                    .build();
        var getNamespaceResponse = osClient.getNamespace(getNamespaceRequest);
        var osNamespace = getNamespaceResponse.getValue();
        System.out.println(logPrefix+"Your object storage namespace: "+osNamespace);

        switch(clientType) {
            case "producer":
                var bucketName = args[5];
                var filePathStr = args[6];
                var filePath = Paths.get(filePathStr);

                // Check the file
                var isExisting = Files.exists(filePath);
                var isRegularFile = Files.isRegularFile(filePath);
                if(!isExisting || !isRegularFile) break;
                System.out.println(logPrefix+"Found file: "+filePathStr+" ("+Files.size(filePath)/1024+"kB)");

                FileInputStream input = new FileInputStream(filePathStr);
                try(input) {
                    var objectName = filePath.getFileName().toString();
                    var osPathToObject = "/n/"+osNamespace+"/b/"+bucketName+"/o/"+objectName;
                    System.out.println(logPrefix+"Uploading the file as "+osPathToObject);
                    
                    // Upload the object
                    // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Object/PutObject
                    var putObjectRequest = PutObjectRequest.builder()
                                                .namespaceName(osNamespace)
                                                .bucketName(bucketName)
                                                .objectName(objectName)
                                                .putObjectBody(input)
                                                .build();
                    var putObjectResponse = osClient.putObject(putObjectRequest);

                    if(putObjectResponse.get__httpStatusCode__()==200) {
                        System.out.println(logPrefix+"Successfully uploaded the file");

                        // Prepare PutMessagesDetails
                        var messages = new ArrayList<PutMessagesDetailsEntry>();
                        messages.add(PutMessagesDetailsEntry.builder()
                                        .value(osPathToObject.getBytes())
                                        .build());
                        var putMessagesDetails = PutMessagesDetails.builder().messages(messages).build();
                        
                        // Put message to the stream
                        // https://docs.oracle.com/en-us/iaas/api/#/en/streaming/20180418/Message/PutMessages
                        var putMessagesRequest = PutMessagesRequest.builder()
                                                    .streamId(streamId)
                                                    .putMessagesDetails(putMessagesDetails)
                                                    .build();
                        var putMessagesResponse = streamClient.putMessages(putMessagesRequest);

                        if(putMessagesResponse.get__httpStatusCode__()==200)
                            System.out.println(logPrefix+"Successfully published the message to the stream");
                    }
                }

                break;
            case "consumer":

                break;
        }
    }
}

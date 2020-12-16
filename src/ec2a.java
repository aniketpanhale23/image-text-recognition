import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.Label;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.System.exit;

public class ec2a {

    public static void main(String[] args) {
        // Credentials for AWS Account
        String aws_access_key_id = "KEY ID";
        String aws_secret_access_key = "YOUR KEY";
        String aws_session_token = "YOUR TOKEN";

        // Define variables
        String BUCKET_NAME = "YOUR IMAGES BUCKET";
        String QUEUE_NAME = "YOUR FIFO QUEUE";
        String MESSAGE_GROUP_ID = "YOUR QUEUE MESSAGE GROUP";

        // Make a connection to the AWS Account
        AWSCredentials credentials = new BasicSessionCredentials(aws_access_key_id, aws_secret_access_key, aws_session_token);
        AWSStaticCredentialsProvider awsStaticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);

        // Make a connection to the AWS S3 Bucket
        AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                .withCredentials(awsStaticCredentialsProvider)
                .withRegion("us-east-1").build();

        // Make a connection to the AWS Rekognition Service
        AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.standard()
                .withCredentials(awsStaticCredentialsProvider)
                .withRegion("us-east-1").build();

        // Make a connection to the AWS SQS Service
        AmazonSQS sqs = AmazonSQSClientBuilder.standard()
                .withCredentials(awsStaticCredentialsProvider)
                .withRegion("us-east-1").build();

        ObjectListing objectListing = null;
        try {
            objectListing = s3.listObjects(new ListObjectsRequest().withBucketName(BUCKET_NAME));
            Map<String, String> attributes = new HashMap<>();
            attributes.put("FifoQueue", "true");
            attributes.put("ContentBasedDeduplication", "true");
            sqs.createQueue(new CreateQueueRequest().withQueueName(QUEUE_NAME).withAttributes(attributes));
            String queueUrl = sqs.getQueueUrl(QUEUE_NAME).getQueueUrl();

            for(S3ObjectSummary obj : objectListing.getObjectSummaries()) {
                S3Object s3Object = s3.getObject(new GetObjectRequest(BUCKET_NAME, obj.getKey()));
                System.out.println("Fetching image: " + obj.getKey() + " from the bucket: " + BUCKET_NAME);

                // Detect cars from S3 Bucket
                DetectLabelsRequest request = new DetectLabelsRequest()
                        .withImage(new Image().withS3Object(new com.amazonaws.services.rekognition.model.S3Object().withName(s3Object.getKey()).withBucket(BUCKET_NAME)))
                        .withMaxLabels(5).withMinConfidence(75F);
                DetectLabelsResult result = rekognitionClient.detectLabels(request);

                // Check if a car is detected
                List<Label> labels = result.getLabels();
                for (Label label : labels) {
                    String detected = label.getName();
                    Float confidence = label.getConfidence();
                    System.out.println("Image: " + s3Object.getKey() + " Label: " + detected + " Confidence: " + confidence);
                    if (detected.equals("Car")) {
                        System.out.println("Detected a " + detected + " in " + s3Object.getKey() + " with confidence of " + confidence.toString() + "%");

                        // Prepare the message to push in the FIFO Queue
                        SendMessageRequest send_msg_request = new SendMessageRequest()
                                .withQueueUrl(queueUrl)
                                .withMessageBody(s3Object.getKey());
                        send_msg_request.setMessageGroupId(MESSAGE_GROUP_ID);

                        // Push the image name in the FIFO Queue
                        sqs.sendMessage(send_msg_request);
                        System.out.println("Pushed " + s3Object.getKey() + "in the SQS FIFO Queue " + QUEUE_NAME);
                    }
                }
            }

            // Append -1 at the end of the FIFO Queue
            SendMessageRequest send_msg_request = new SendMessageRequest()
                    .withQueueUrl(queueUrl)
                    .withMessageBody("-1");
            send_msg_request.setMessageGroupId(MESSAGE_GROUP_ID);

            sqs.sendMessage(send_msg_request);

        } catch (AmazonServiceException e) {
            if (e.getErrorCode().equals("QueueAlreadyExists")) { }
            else if (e.getErrorCode().equals("ExpiredToken")) {
                System.out.println("Your credentials have expired. Please update aws_access_key_id, aws_secret_access_key and aws_session_token.");
                exit(0);
            } else {
                e.printStackTrace();
            }
        }
    }
}

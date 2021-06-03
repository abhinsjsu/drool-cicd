package com.amazon.awsgurufrontendservice.s3;

import com.amazonaws.guru.common.exceptions.NotFoundException;
import com.google.common.collect.ImmutableMap;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;

@Log4j2
public final class AccountIdToS3BucketNameMapper {
    private static ImmutableMap<String, String> ACCOUNTID_S3BUCKETNAME_CANARIES_MAP = ImmutableMap.<String, String>builder()
            // AWSGuruFEServiceCanaries
            .put("567036573317", "codeguru-reviewer-6a9bb283-726f-4928-b995-96417a2b9d77")// beta us-west-2
            .put("176781407354", "codeguru-reviewer-5b29bf87-5c1c-459b-a774-4af854f3a38c")// gamma us-east-1
            .put("345387765506", "codeguru-reviewer-ab389e65-50d2-4630-9dbd-8ed7f8103733")// gamma us-west-2
            .put("963078775295", "codeguru-reviewer-fd2cbe90-30fb-40c9-9f7c-e2e3ef16b58a")// preprod eu-north-1
            .put("211482421922", "codeguru-reviewer-37a28635-196b-4c94-aac5-fab3b96c5066")// preprod us-east-2
            .put("735520943388", "codeguru-reviewer-3d6ce96c-4855-4704-8343-2f2bf741f63b")// preprod eu-west-1
            .put("130659420253", "codeguru-reviewer-6ad9a699-e9c0-4039-9134-6c27d8f430e4")// preprod eu-central-1
            .put("355679184711", "codeguru-reviewer-1e3bc747-db3a-48e9-8d75-909e64d76746")// preprod us-east-1
            .put("820854057569", "codeguru-reviewer-c08313cc-029f-467a-94ba-8e73b815c127")// preprod eu-west-2
            .put("768782632694", "codeguru-reviewer-1ebf32d9-a99b-4df9-b19f-fd5d662a7804")// preprod ap-northeast-1
            .put("918900624119", "codeguru-reviewer-e1178434-d686-4031-b6d9-d7460db6f8a0")// preprod us-west-2
            .put("101080249358", "codeguru-reviewer-9b9db6b7-2b2b-4565-b6d6-945c7481f5d7")// preprod ap-southeast-1
            .put("541806509521", "codeguru-reviewer-2da97a8e-79f8-4774-a491-25003673c75b")// preprod ap-southeast-2
            .put("676721210805", "codeguru-reviewer-6338e845-b790-478c-bc09-a000d2578175")// prod eu-north-1
            .put("882303630524", "codeguru-reviewer-d087524e-7360-4444-a7e8-c30997d10a48")// prod us-east-2
            .put("370924606840", "codeguru-reviewer-a5d7dd65-a2b8-4ec9-bea6-a97901fb841a")// prod eu-west-1
            .put("343196631887", "codeguru-reviewer-50032bc1-3eb7-4fce-bda0-f16b2602b37c")// prod eu-central-1
            .put("597817710249", "codeguru-reviewer-51dc038d-2232-43df-8181-cefa1bc92030")// prod us-east-1
            .put("743269522945", "codeguru-reviewer-d1b8f1d7-7af9-433f-9591-13231eb86fae")// prod eu-west-2
            .put("022399190478", "codeguru-reviewer-0055e48c-2803-49d0-ae0c-a7624ed4288f")// prod ap-northeast-1
            .put("650354007026", "codeguru-reviewer-4213b060-a478-4e69-ae89-4d2ca90eea0d")// prod us-west-2
            .put("284991723834", "codeguru-reviewer-b8f55a27-c41f-43f3-9307-146563e76286")// prod ap-southeast-1
            .put("603981226625", "codeguru-reviewer-50e1052e-fcfc-4a9d-bb6a-b86e3422a14c")// prod ap-southeast-2
            .build();

    private static ImmutableMap<String, ImmutableMap<String, String>> ACCOUNTID_S3BUCKETNAME_E2E_MAP =
            ImmutableMap.<String, ImmutableMap<String, String>>builder()
            .put("707034700413", ImmutableMap.<String, String>builder() // beta
                    .put("us-west-2", "codeguru-reviewer-092b2c19-fcf7-4fe6-8e83-b04c75ff4ca4")
                    .build())
            .put("026330583199", ImmutableMap.<String, String>builder() // gamma
                    .put("us-east-1", "codeguru-reviewer-bfb85899-8bfb-4b1b-9271-8ff18fe58f6e")
                    .put("us-west-2", "codeguru-reviewer-bec84e64-9af7-481e-aa0e-1f77f13de46d")
                    .build())
            .put("517502510590", ImmutableMap.<String, String>builder() // preprod
                    .put("eu-north-1", "codeguru-reviewer-ef51d8f1-1354-4b79-87b3-16247dbb05af")
                    .put("us-east-2", "codeguru-reviewer-a9d365d9-ad77-4042-9b32-4c4db51ea664")
                    .put("eu-west-1", "codeguru-reviewer-5f9aa331-46d0-46ef-bb1f-39f7b0d87d27")
                    .put("eu-central-1", "codeguru-reviewer-b1eda6c3-16bc-4895-a493-02c6728113d4")
                    .put("us-east-1", "codeguru-reviewer-9d0bb342-30f5-4512-be2d-7d32a8e57229")
                    .put("eu-west-2", "codeguru-reviewer-28f3c4df-c02b-4afc-8a2f-d0b06f282c0e")
                    .put("ap-northeast-1", "codeguru-reviewer-b43dccdc-b840-400d-a160-b8a396cf2fa1")
                    .put("us-west-2", "codeguru-reviewer-8fa9e2c9-6c2b-44a8-8a19-d5a8e5e1e747")
                    .put("ap-southeast-1", "codeguru-reviewer-b9b6d250-f5e5-48c1-b025-ed77b2199496")
                    .put("ap-southeast-2", "codeguru-reviewer-6bbba7ce-8926-4ffe-916d-c3dc257897d0")
                    .build())
            .put("320330891682", ImmutableMap.<String, String>builder() // prod
                     .put("eu-north-1", "codeguru-reviewer-c9d338d7-1f6c-48f6-87cb-f48cfb7110a2")
                     .put("us-east-2", "codeguru-reviewer-7f0780a5-0b61-48b1-b1a5-733dcb7db3a0")
                     .put("eu-west-1", "codeguru-reviewer-7eb5f95b-db98-40ad-942f-897bf4801e5f")
                     .put("eu-central-1", "codeguru-reviewer-d5f979f4-831c-493a-986b-791e43dda1bb")
                     .put("us-east-1", "codeguru-reviewer-e6390539-c99a-49ba-9ff8-ddbfee438fa2")
                     .put("eu-west-2", "codeguru-reviewer-472d574f-25dd-40e2-8201-4e128d4941ad")
                     .put("ap-northeast-1", "codeguru-reviewer-80d83dbe-fcf2-4749-8125-8a79e478bb6c")
                     .put("us-west-2", "codeguru-reviewer-bf630944-b479-4fa2-9f4f-876d06487f65")
                     .put("ap-southeast-1", "codeguru-reviewer-52dc0f0f-36e7-46b3-b982-e7989ab35dde")
                     .put("ap-southeast-2", "codeguru-reviewer-4babfed9-74c7-428a-9154-4232085305a8")
                     .build())
            .build();

    public static String getS3BucketName(final String accountId, final String region) {
        String bucketName = ACCOUNTID_S3BUCKETNAME_CANARIES_MAP.get(accountId);
        if (StringUtils.isNotBlank(bucketName)) {
            return bucketName;
        }
        ImmutableMap<String, String> e2eRegionBucketNameMap = ACCOUNTID_S3BUCKETNAME_E2E_MAP.get(accountId);
        if (e2eRegionBucketNameMap == null || StringUtils.isBlank(e2eRegionBucketNameMap.get(region))) {
            log.error("Currently no bucket is set for account {} in region {}."
                              + "Please provide an account Id to bucket name map in AccountIdToS3BucketNameMapper",
                      accountId, region);
            throw new NotFoundException(String.format("S3 bucket for account %s in region %s not found", accountId, region));
        }
        return e2eRegionBucketNameMap.get(region);
    }
}

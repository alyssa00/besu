/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.enclave.Enclave;
import org.hyperledger.besu.enclave.types.PrivacyGroup;
import org.hyperledger.besu.enclave.types.ReceiveResponse;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.evm.log.Log;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RestrictedMultiTenancyPrivacyControllerTest {

  private static final String ENCLAVE_PUBLIC_KEY1 = "Ko2bVqD+nNlNYL5EE7y3IdOnviftjiizpjRt+HTuFBs=";
  private static final String ENCLAVE_PUBLIC_KEY2 = "OnviftjiizpjRt+HTuFBsKo2bVqD+nNlNYL5EE7y3Id=";
  private static final String PRIVACY_GROUP_ID = "nNlNYL5EE7y3IdM=";
  private static final String ENCLAVE_KEY = "Ko2bVqD";
  private static final ArrayList<Log> LOGS = new ArrayList<>();
  private static final PrivacyGroup PANTHEON_PRIVACY_GROUP =
      new PrivacyGroup("", PrivacyGroup.Type.PANTHEON, "", "", Collections.emptyList());
  private static final PrivacyGroup PANTHEON_GROUP_WITH_ENCLAVE_KEY_1 =
      new PrivacyGroup(
          PRIVACY_GROUP_ID, PrivacyGroup.Type.PANTHEON, "", "", List.of(ENCLAVE_PUBLIC_KEY1));

  @Mock private PrivacyController privacyController;
  @Mock private Enclave enclave;

  private RestrictedMultiTenancyPrivacyController multiTenancyPrivacyController;

  @Before
  public void setup() {
    multiTenancyPrivacyController =
        new RestrictedMultiTenancyPrivacyController(
            privacyController, Optional.of(BigInteger.valueOf(2018)), enclave, false);
  }

  @Test
  public void
      sendsEeaTransactionWithMatchingPrivateFromAndPrivacyUserIdAndProducesSuccessfulResponse() {
    final PrivateTransaction transaction =
        PrivateTransaction.builder()
            .privateFrom(Bytes.fromBase64String(ENCLAVE_PUBLIC_KEY1))
            .build();

    when(privacyController.createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(PANTHEON_PRIVACY_GROUP)))
        .thenReturn(ENCLAVE_KEY);

    final String enclaveKey =
        multiTenancyPrivacyController.createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(PANTHEON_PRIVACY_GROUP));
    assertThat(enclaveKey).isEqualTo(ENCLAVE_KEY);
    verify(privacyController)
        .createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(PANTHEON_PRIVACY_GROUP));
  }

  @Test
  public void sendsBesuTransactionWithPrivacyUserIdInPrivacyGroupAndProducesSuccessfulResponse() {
    final PrivateTransaction transaction =
        PrivateTransaction.builder()
            .privateFrom(Bytes.fromBase64String(ENCLAVE_PUBLIC_KEY1))
            .privacyGroupId(Bytes.fromBase64String(PRIVACY_GROUP_ID))
            .build();

    final PrivacyGroup privacyGroupWithPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID,
            PrivacyGroup.Type.PANTHEON,
            "",
            "",
            List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2));
    when(privacyController.createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(privacyGroupWithPrivacyUserId)))
        .thenReturn(ENCLAVE_KEY);
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID)).thenReturn(privacyGroupWithPrivacyUserId);

    final String response =
        multiTenancyPrivacyController.createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(privacyGroupWithPrivacyUserId));
    assertThat(response).isEqualTo(ENCLAVE_KEY);
    verify(privacyController)
        .createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(privacyGroupWithPrivacyUserId));
    verify(enclave).retrievePrivacyGroup(PRIVACY_GROUP_ID);
  }

  @Test
  public void sendEeaTransactionFailsWithValidationExceptionWhenPrivateFromDoesNotMatch() {
    final PrivateTransaction transaction =
        PrivateTransaction.builder()
            .privateFrom(Bytes.fromBase64String(ENCLAVE_PUBLIC_KEY2))
            .build();

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.createPrivateMarkerTransactionPayload(
                    transaction, ENCLAVE_PUBLIC_KEY1, Optional.empty()))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Transaction privateFrom must match enclave public key");

    verify(privacyController, never())
        .createPrivateMarkerTransactionPayload(transaction, ENCLAVE_PUBLIC_KEY1, Optional.empty());
  }

  @Test
  public void sendBesuTransactionFailsWithValidationExceptionWhenPrivateFromDoesNotMatch() {
    final PrivateTransaction transaction =
        PrivateTransaction.builder()
            .privateFrom(Bytes.fromBase64String(ENCLAVE_PUBLIC_KEY2))
            .privacyGroupId(Bytes.fromBase64String(PRIVACY_GROUP_ID))
            .build();

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.createPrivateMarkerTransactionPayload(
                    transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(PANTHEON_PRIVACY_GROUP)))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Transaction privateFrom must match enclave public key");

    verify(privacyController, never())
        .createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(PANTHEON_PRIVACY_GROUP));
  }

  @Test
  public void
      sendBesuTransactionFailsWithValidationExceptionWhenPrivacyGroupDoesNotContainPrivacyUserId() {
    final PrivateTransaction transaction =
        PrivateTransaction.builder()
            .privateFrom(Bytes.fromBase64String(ENCLAVE_PUBLIC_KEY1))
            .privacyGroupId(Bytes.fromBase64String(PRIVACY_GROUP_ID))
            .build();

    final PrivacyGroup privacyGroupWithoutPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID, PrivacyGroup.Type.PANTHEON, "", "", List.of(ENCLAVE_PUBLIC_KEY2));
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(privacyGroupWithoutPrivacyUserId);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.createPrivateMarkerTransactionPayload(
                    transaction,
                    ENCLAVE_PUBLIC_KEY1,
                    Optional.of(privacyGroupWithoutPrivacyUserId)))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Privacy group must contain the enclave public key");

    verify(privacyController, never())
        .createPrivateMarkerTransactionPayload(
            transaction, ENCLAVE_PUBLIC_KEY1, Optional.of(privacyGroupWithoutPrivacyUserId));
  }

  @Test
  public void retrieveTransactionDelegatesToPrivacyController() {
    final ReceiveResponse delegateRetrieveResponse =
        new ReceiveResponse(new byte[] {}, PRIVACY_GROUP_ID, ENCLAVE_KEY);
    when(privacyController.retrieveTransaction(ENCLAVE_KEY, ENCLAVE_PUBLIC_KEY1))
        .thenReturn(delegateRetrieveResponse);

    final ReceiveResponse multiTenancyRetrieveResponse =
        multiTenancyPrivacyController.retrieveTransaction(ENCLAVE_KEY, ENCLAVE_PUBLIC_KEY1);
    assertThat(multiTenancyRetrieveResponse)
        .isEqualToComparingFieldByField(delegateRetrieveResponse);
    verify(privacyController).retrieveTransaction(ENCLAVE_KEY, ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void createPrivacyGroupDelegatesToPrivacyController() {
    final List<String> addresses = List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2);
    final PrivacyGroup delegatePrivacyGroup =
        new PrivacyGroup(
            PRIVACY_GROUP_ID, PrivacyGroup.Type.PANTHEON, "name", "description", addresses);

    when(privacyController.createPrivacyGroup(
            addresses, "name", "description", ENCLAVE_PUBLIC_KEY1))
        .thenReturn(delegatePrivacyGroup);

    final PrivacyGroup privacyGroup =
        multiTenancyPrivacyController.createPrivacyGroup(
            addresses, "name", "description", ENCLAVE_PUBLIC_KEY1);
    assertThat(privacyGroup).isEqualToComparingFieldByField(delegatePrivacyGroup);
    verify(privacyController)
        .createPrivacyGroup(addresses, "name", "description", ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void deletesPrivacyGroupWhenPrivacyUserIdInPrivacyGroup() {
    final PrivacyGroup privacyGroupWithPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID,
            PrivacyGroup.Type.PANTHEON,
            "",
            "",
            List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2));
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID)).thenReturn(privacyGroupWithPrivacyUserId);
    when(privacyController.deletePrivacyGroup(PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1))
        .thenReturn(ENCLAVE_PUBLIC_KEY1);

    final String privacyGroupId =
        multiTenancyPrivacyController.deletePrivacyGroup(PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1);
    assertThat(privacyGroupId).isEqualTo(ENCLAVE_PUBLIC_KEY1);
    verify(privacyController).deletePrivacyGroup(PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void
      deletePrivacyGroupFailsWithValidationExceptionWhenPrivacyGroupDoesNotContainPrivacyUserId() {
    final PrivacyGroup privacyGroupWithoutPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID, PrivacyGroup.Type.PANTHEON, "", "", List.of(ENCLAVE_PUBLIC_KEY2));
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(privacyGroupWithoutPrivacyUserId);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.deletePrivacyGroup(
                    PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Privacy group must contain the enclave public key");
  }

  @Test
  public void findsPrivacyGroupWhenPrivacyUserIdInAddresses() {
    final List<String> addresses = List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2);
    final PrivacyGroup privacyGroup =
        new PrivacyGroup(
            PRIVACY_GROUP_ID,
            PrivacyGroup.Type.PANTHEON,
            "",
            "",
            List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2));
    when(privacyController.findOffchainPrivacyGroupByMembers(addresses, ENCLAVE_PUBLIC_KEY1))
        .thenReturn(new PrivacyGroup[] {privacyGroup});

    final PrivacyGroup[] privacyGroups =
        multiTenancyPrivacyController.findOffchainPrivacyGroupByMembers(
            addresses, ENCLAVE_PUBLIC_KEY1);
    assertThat(privacyGroups).hasSize(1);
    assertThat(privacyGroups[0]).isEqualToComparingFieldByField(privacyGroup);
    verify(privacyController).findOffchainPrivacyGroupByMembers(addresses, ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void findPrivacyGroupFailsWithValidationExceptionWhenPrivacyUserIdNotInAddresses() {
    final List<String> addresses = List.of(ENCLAVE_PUBLIC_KEY2);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.findOffchainPrivacyGroupByMembers(
                    addresses, ENCLAVE_PUBLIC_KEY1))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Privacy group addresses must contain the enclave public key");
  }

  @Test
  public void determinesEeaNonceWhenPrivateFromMatchesPrivacyUserId() {
    final String[] privateFor = {ENCLAVE_PUBLIC_KEY2};
    when(privacyController.determineEeaNonce(
            ENCLAVE_PUBLIC_KEY1, privateFor, Address.ZERO, ENCLAVE_PUBLIC_KEY1))
        .thenReturn(10L);

    final long nonce =
        multiTenancyPrivacyController.determineEeaNonce(
            ENCLAVE_PUBLIC_KEY1, privateFor, Address.ZERO, ENCLAVE_PUBLIC_KEY1);
    assertThat(nonce).isEqualTo(10);
    verify(privacyController)
        .determineEeaNonce(ENCLAVE_PUBLIC_KEY1, privateFor, Address.ZERO, ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void
      determineEeaNonceFailsWithValidationExceptionWhenPrivateFromDoesNotMatchPrivacyUserId() {
    final String[] privateFor = {ENCLAVE_PUBLIC_KEY2};
    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.determineEeaNonce(
                    ENCLAVE_PUBLIC_KEY2, privateFor, Address.ZERO, ENCLAVE_PUBLIC_KEY1))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Transaction privateFrom must match enclave public key");
  }

  @Test
  public void determineBesuNonceWhenPrivacyUserIdInPrivacyGroup() {
    final PrivacyGroup privacyGroupWithPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID,
            PrivacyGroup.Type.PANTHEON,
            "",
            "",
            List.of(ENCLAVE_PUBLIC_KEY1, ENCLAVE_PUBLIC_KEY2));
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID)).thenReturn(privacyGroupWithPrivacyUserId);
    when(privacyController.determineBesuNonce(Address.ZERO, PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1))
        .thenReturn(10L);

    final long nonce =
        multiTenancyPrivacyController.determineBesuNonce(
            Address.ZERO, PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1);
    assertThat(nonce).isEqualTo(10);
    verify(privacyController)
        .determineBesuNonce(Address.ZERO, PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1);
  }

  @Test
  public void determineBesuNonceFailsWithValidationExceptionWhenPrivacyUserIdNotInPrivacyGroup() {
    final PrivacyGroup privacyGroupWithoutPrivacyUserId =
        new PrivacyGroup(
            PRIVACY_GROUP_ID, PrivacyGroup.Type.PANTHEON, "", "", List.of(ENCLAVE_PUBLIC_KEY2));
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(privacyGroupWithoutPrivacyUserId);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.determineBesuNonce(
                    Address.ZERO, PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1))
        .isInstanceOf(MultiTenancyValidationException.class)
        .hasMessage("Privacy group must contain the enclave public key");
  }

  @Test
  public void simulatePrivateTransactionWorksForValidEnclaveKey() {
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);
    when(privacyController.simulatePrivateTransaction(any(), any(), any(), any(long.class)))
        .thenReturn(
            Optional.of(
                TransactionProcessingResult.successful(
                    LOGS, 0, 0, Bytes.EMPTY, ValidationResult.valid())));
    final Optional<TransactionProcessingResult> result =
        multiTenancyPrivacyController.simulatePrivateTransaction(
            PRIVACY_GROUP_ID,
            ENCLAVE_PUBLIC_KEY1,
            new CallParameter(Address.ZERO, Address.ZERO, 0, Wei.ZERO, Wei.ZERO, Bytes.EMPTY),
            1);

    assertThat(result.isPresent()).isTrue();
    assertThat(result.get().getValidationResult().isValid()).isTrue();
  }

  @Test
  public void simulatePrivateTransactionFailsForInvalidEnclaveKey() {
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.simulatePrivateTransaction(
                    PRIVACY_GROUP_ID,
                    ENCLAVE_PUBLIC_KEY2,
                    new CallParameter(
                        Address.ZERO, Address.ZERO, 0, Wei.ZERO, Wei.ZERO, Bytes.EMPTY),
                    1))
        .hasMessage("Privacy group must contain the enclave public key");
  }

  @Test
  public void getContractCodeWorksForValidEnclaveKey() {
    final Bytes contractCode = Bytes.fromBase64String("ZXhhbXBsZQ==");

    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);
    when(privacyController.getContractCode(any(), any(), any(), any()))
        .thenReturn(Optional.of(contractCode));

    final Optional<Bytes> result =
        multiTenancyPrivacyController.getContractCode(
            PRIVACY_GROUP_ID, Address.ZERO, Hash.ZERO, ENCLAVE_PUBLIC_KEY1);

    assertThat(result).isPresent().hasValue(contractCode);
  }

  @Test
  public void getContractCodeFailsForInvalidEnclaveKey() {
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);

    assertThatThrownBy(
            () ->
                multiTenancyPrivacyController.getContractCode(
                    PRIVACY_GROUP_ID, Address.ZERO, Hash.ZERO, ENCLAVE_PUBLIC_KEY2))
        .hasMessage("Privacy group must contain the enclave public key");
  }

  @Test
  public void verifyPrivacyGroupMatchesEnclaveKeySucceeds() {
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);

    multiTenancyPrivacyController.verifyPrivacyGroupContainsPrivacyUserId(
        PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY1);

    verify(enclave).retrievePrivacyGroup(eq(PRIVACY_GROUP_ID));
  }

  @Test(expected = MultiTenancyValidationException.class)
  public void verifyPrivacyGroupDoesNotMatchEnclaveKeyThrowsException() {
    when(enclave.retrievePrivacyGroup(PRIVACY_GROUP_ID))
        .thenReturn(PANTHEON_GROUP_WITH_ENCLAVE_KEY_1);

    multiTenancyPrivacyController.verifyPrivacyGroupContainsPrivacyUserId(
        PRIVACY_GROUP_ID, ENCLAVE_PUBLIC_KEY2);
  }
}

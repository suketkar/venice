package com.linkedin.venice.controller;

import com.linkedin.venice.AdminTool;
import com.linkedin.venice.common.VeniceSystemStoreType;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.MultiStoreResponse;
import com.linkedin.venice.controllerapi.NewStoreResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceTwoLayerMultiColoMultiClusterWrapper;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TestAdminToolInMultiDatacenterSetting {

  private static final int TEST_TIMEOUT = 120_000; // ms

  private static final int NUMBER_OF_CHILD_DATACENTERS = 2; // DO NOT CHANGE
  private static final int NUMBER_OF_CLUSTERS = 1;

  private List<VeniceMultiClusterWrapper> childDatacenters;
  private List<VeniceControllerWrapper> parentControllers;

  private VeniceTwoLayerMultiColoMultiClusterWrapper multiColoMultiClusterWrapper;

  @BeforeClass
  public void setUp() {
    multiColoMultiClusterWrapper = ServiceFactory.getVeniceTwoLayerMultiColoMultiClusterWrapper(
        NUMBER_OF_CHILD_DATACENTERS,
        NUMBER_OF_CLUSTERS,
        2,
        2,
        2,
        2);
    childDatacenters = multiColoMultiClusterWrapper.getClusters();
    parentControllers = multiColoMultiClusterWrapper.getParentControllers();
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    multiColoMultiClusterWrapper.close();
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testBackfillOfMetaSystemStore() throws Exception {
    String clusterName = multiColoMultiClusterWrapper.getClusters().get(0).getClusterNames()[0];
    String testStoreName = Utils.getUniqueString("test-store");

    VeniceControllerWrapper parentController = parentControllers.stream().filter(c -> c.isLeaderController(clusterName)).findAny().get();
    ControllerClient parentControllerClient =
        ControllerClient.constructClusterControllerClient(clusterName, parentController.getControllerUrl());
    ControllerClient dc0Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(0).getControllerConnectString());
    ControllerClient dc1Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(1).getControllerConnectString());

    // store shouldn't exist
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
      StoreResponse storeResponse = parentControllerClient.getStore(testStoreName);
      assertTrue(storeResponse.isError());
    });

    // Create a test store
    NewStoreResponse newStoreResponse = parentControllerClient.retryableRequest(5,
        c -> c.createNewStore(testStoreName, "test", "\"string\"", "\"string\""));
    assertFalse(newStoreResponse.isError(), "Test store creation failed - " + newStoreResponse.getError());

    verifyMetaSystemStoreStatus(parentControllerClient, "parentController" , testStoreName, false);
    verifyMetaSystemStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, false);
    verifyMetaSystemStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, false);

    String[] adminToolArgs = {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--backfill-system-stores",
        "--system-store-type", "meta_store"
    };
    AdminTool.main(adminToolArgs);

    verifyMetaSystemStoreStatus(parentControllerClient, "parentController" , testStoreName, true);
    verifyMetaSystemStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, true);
    verifyMetaSystemStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, true);

    verifyListStoreContainsMetaSystemStore(parentControllerClient, "parentController" , testStoreName);
    verifyListStoreContainsMetaSystemStore(dc0Client, "dc0ControllerClient", testStoreName);
    verifyListStoreContainsMetaSystemStore(dc1Client, "dc1ControllerClient", testStoreName);
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testBackfillOfDavinciPushStatusStore() throws Exception {
    String clusterName = multiColoMultiClusterWrapper.getClusters().get(0).getClusterNames()[0];
    String testStoreName = Utils.getUniqueString("test-store");

    VeniceControllerWrapper parentController = parentControllers.stream().filter(c -> c.isLeaderController(clusterName)).findAny().get();
    ControllerClient parentControllerClient =
        ControllerClient.constructClusterControllerClient(clusterName, parentController.getControllerUrl());
    ControllerClient dc0Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(0).getControllerConnectString());
    ControllerClient dc1Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(1).getControllerConnectString());

    // store shouldn't exist
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
      StoreResponse storeResponse = parentControllerClient.getStore(testStoreName);
      assertTrue(storeResponse.isError());
    });

    // Create a test store
    NewStoreResponse newStoreResponse = parentControllerClient.retryableRequest(5,
        c -> c.createNewStore(testStoreName, "test", "\"string\"", "\"string\""));
    assertFalse(newStoreResponse.isError(), "Test store creation failed - " + newStoreResponse.getError());

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, false);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, false);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, false);

    String[] adminToolArgs = {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--backfill-system-stores",
        "--system-store-type", "davinci_push_status_store"
    };
    AdminTool.main(adminToolArgs);

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, true);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, true);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, true);

    verifyListStoreContainsPushStatusStore(parentControllerClient, "parentController" , testStoreName);
    verifyListStoreContainsPushStatusStore(dc0Client, "dc0ControllerClient", testStoreName);
    verifyListStoreContainsPushStatusStore(dc1Client, "dc1ControllerClient", testStoreName);
  }

  @Test(timeOut = 2 * TEST_TIMEOUT)
  public void testEnableDavinciPushStatusStoreUsingUpdateStoreCommand() throws Exception {
    String clusterName = multiColoMultiClusterWrapper.getClusters().get(0).getClusterNames()[0];
    String testStoreName = Utils.getUniqueString("test-store");

    VeniceControllerWrapper parentController = parentControllers.stream().filter(c -> c.isLeaderController(clusterName)).findAny().get();
    ControllerClient parentControllerClient =
        ControllerClient.constructClusterControllerClient(clusterName, parentController.getControllerUrl());
    ControllerClient dc0Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(0).getControllerConnectString());
    ControllerClient dc1Client =
        ControllerClient.constructClusterControllerClient(clusterName, childDatacenters.get(1).getControllerConnectString());

    // store shouldn't exist
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
      StoreResponse storeResponse = parentControllerClient.getStore(testStoreName);
      assertTrue(storeResponse.isError());
    });

    // Create a test store
    NewStoreResponse newStoreResponse = parentControllerClient.retryableRequest(5,
        c -> c.createNewStore(testStoreName, "test", "\"string\"", "\"string\""));
    assertFalse(newStoreResponse.isError(), "Test store creation failed - " + newStoreResponse.getError());

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, false);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, false);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, false);

    String[] adminToolArgs = {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--store", testStoreName,
        "--backfill-system-stores",
        "--system-store-type", "davinci_push_status_store"
    };
    AdminTool.main(adminToolArgs);

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, true);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, true);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, true);

    // try some random update store operation, and it shouldn't disable davinci push status store
    adminToolArgs = new String[] {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--store", testStoreName,
        "--update-store",
        "--bootstrap-to-online-timeout", "1",
    };
    AdminTool.main(adminToolArgs);

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, true);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, true);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, true);

    // replicate all configs shouldn't disable davinci push status store
    adminToolArgs = new String[] {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--store", testStoreName,
        "--update-store",
        "--replicate-all-configs", "true"
    };
    AdminTool.main(adminToolArgs);

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, true);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, true);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, true);

    adminToolArgs = new String[] {
        "--url", parentControllerClient.getLeaderControllerUrl(),
        "--cluster", clusterName,
        "--store", testStoreName,
        "--update-store",
        "--disable-davinci-push-status-store",
    };
    AdminTool.main(adminToolArgs);

    verifyPushStatusStoreStatus(parentControllerClient, "parentController" , testStoreName, false);
    verifyPushStatusStoreStatus(dc0Client, "dc0ControllerClient", testStoreName, false);
    verifyPushStatusStoreStatus(dc1Client, "dc1ControllerClient", testStoreName, false);
  }

  private void verifyMetaSystemStoreStatus(ControllerClient controllerClient, String clientName, String testStoreName, boolean isEnabled) {
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
      StoreResponse storeResponse = controllerClient.getStore(testStoreName);
      assertFalse(storeResponse.isError());
      assertEquals(storeResponse.getStore().isStoreMetaSystemStoreEnabled(),
          isEnabled,
          "Meta store is not " +  (isEnabled ? "enabled" : "disabled")  + ". Controller: " + clientName);
    });
  }

  private void verifyPushStatusStoreStatus(ControllerClient controllerClient, String clientName, String testStoreName, boolean isEnabled) {
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
      StoreResponse storeResponse = controllerClient.getStore(testStoreName);
      assertFalse(storeResponse.isError());
      assertEquals(storeResponse.getStore().isDaVinciPushStatusStoreEnabled(),
          isEnabled,
          "Push status store is not " +  (isEnabled ? "enabled" : "disabled")  + ". Controller: " + clientName);
    });
  }

  private void verifyListStoreContainsMetaSystemStore(ControllerClient controllerClient, String clientName, String testStoreName) {
    String metaStoreName = VeniceSystemStoreType.META_STORE.getSystemStoreName(testStoreName);
    MultiStoreResponse multiStoreResponse = controllerClient.queryStoreList();
    assertFalse(multiStoreResponse.isError());
    Set<String> allStores = new HashSet<>(Arrays.asList(multiStoreResponse.getStores()));
    assertTrue(allStores.contains(metaStoreName), metaStoreName + " is not present in list store response with " + clientName);
  }

  private void verifyListStoreContainsPushStatusStore(ControllerClient controllerClient, String clientName, String testStoreName) {
    String pushStatusStoreName = VeniceSystemStoreType.DAVINCI_PUSH_STATUS_STORE.getSystemStoreName(testStoreName);
    MultiStoreResponse multiStoreResponse = controllerClient.queryStoreList();
    assertFalse(multiStoreResponse.isError());
    Set<String> allStores = new HashSet<>(Arrays.asList(multiStoreResponse.getStores()));
    assertTrue(allStores.contains(pushStatusStoreName), pushStatusStoreName + " is not present in list store response with " + clientName);
  }
}
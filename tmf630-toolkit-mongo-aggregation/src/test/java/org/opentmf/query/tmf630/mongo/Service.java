package org.opentmf.query.tmf630.mongo;

import java.util.List;

public class Service {

  private List<Characteristic> serviceCharacteristic;

  public Service() {}

  public Service(List<Characteristic> serviceCharacteristic) {
    this.serviceCharacteristic = serviceCharacteristic;
  }

  public List<Characteristic> getServiceCharacteristic() {
    return serviceCharacteristic;
  }

  public void setServiceCharacteristic(List<Characteristic> serviceCharacteristic) {
    this.serviceCharacteristic = serviceCharacteristic;
  }
}

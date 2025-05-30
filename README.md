# hbase-docker

This project is meant to provide an HBase docker for running unit tests.

Note that the container is meant for quick tests, and data may be corrupted or lost on shutdown.

## Quick Start - Docker

    docker pull ghcr.io/geomesa/hbase-docker:2.6.2
    docker run --rm \
      --name hbase \
      -p 16000:16000 -p 16020:16020 -p 8020:8020 -p 2181:2181 \
      --hostname $(hostname -s) \
      ghcr.io/geomesa/hbase-docker:2.6.2

Note that the `hostname` must be set to the hostname of the host in order for HBase's networking to work.

The Hbase connection properties are available in the container:

    docker cp hbase:/opt/hbase/conf/hbase-site.xml .

### Using the Docker with GeoMesa

In order to use GeoMesa, the distributed runtime JAR must be mounted in to the container. The distributed runtime
JAR is available from [GeoMesa](https://github.com/locationtech/geomesa/releases):

    wget 'https://github.com/locationtech/geomesa/releases/download/geomesa-5.3.0/geomesa-hbase_2.12-5.3.0-bin.tar.gz'
    tar -xf geomesa-hbase_2.12-5.3.0-bin.tar.gz
    docker run --rm \
      --name hbase \
      -p 16000:16000 -p 16020:16020 -p 8020:8020 -p 2181:2181 \
      --hostname $(hostname -s) \
      -v "$(pwd)"/geomesa-hbase_2.12-5.3.0/dist/hbase/geomesa-hbase-distributed-runtime-hbase2_2.12-5.3.0.jar:/opt/hbase/lib/geomesa-hbase-distributed-runtime.jar \
      ghcr.io/geomesa/hbase-docker:2.6.2

## Quick Start - Testcontainers

Add the following dependencies:

    <dependency>
      <groupId>org.geomesa.testcontainers</groupId>
      <artifactId>testcontainers-hbase</artifactId>
      <version>1.0.0</version>
      <scope>test</scope>
    </dependency>
    <!-- only required for GeoMesa support -->
    <dependency>
      <groupId>org.locationtech.geomesa</groupId>
      <artifactId>geomesa-hbase-distributed-runtime-hbase2_2.12</artifactId>
      <version>5.3.0</version>
      <scope>test</scope>
    </dependency>

Write unit tests against HBase:

    import org.geomesa.testcontainers.hbase.HBaseContainer;

    static HBaseContainer hbase = new HBaseContainer().withGeoMesaDistributedRuntime();
    
    @BeforeAll
    static void beforeAll() {
      hbase.start();
    }
    
    @AfterAll
    static void afterAll() {
      hbase.stop();
    }

## Ports

Most functionality should work with the following ports exposed:

* `16000` - HBase Master
* `16020` - HBase Region Server
* `8020` - Hadoop NameNode
* `2181` - Zookeeper

## Port Configuration

The following environment variables are supported to override the ports used by various services:

* `HBASE_MASTER_PORT` - override the default Master port
* `HBASE_REGIONSERVER_PORT` - override the default Region Server port
* `NAMENODE_PORT` - override the default Hadoop NameNode port
* `ZOOKEEPER_PORT` - override the default Zookeeper port

## Other Configuration

* `HBASE_SECURITY_ENABLED` - enable `hbase.security.authorization` and the HBase `VisibilityController` for label security

## Tags and Versions

### Tag `2.6`, `2.6.2`

* HBase 2.6.2

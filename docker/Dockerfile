FROM ghcr.io/geomesa/accumulo-uno:2.1.3

ARG HBASE_VERSION=2.6.2
ARG HBASE_HADOOP_VERSION=${HBASE_VERSION}-hadoop3

ENV HBASE_VERSION=${HBASE_VERSION}
ENV HBASE_HOME=/opt/hbase
ENV HBASE_MANAGES_ZK=false

RUN cd "$HBASE_HOME"/.. && \
    curl -L "https://dlcdn.apache.org/hbase/$HBASE_VERSION/hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz" -o "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz" && \
    curl -L "https://downloads.apache.org/hbase/$HBASE_VERSION/hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz.sha512" -o "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz.sha512" && \
    cat "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz.sha512" | tr '\n' ' ' | tr -d ' ' | awk -F ':' '{ print $2 "\t" $1 }' | sha512sum -c - && \
    tar -xf "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz" && \
    ln -s "hbase-${HBASE_HADOOP_VERSION}" hbase && \
    rm "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz" && \
    rm "hbase-${HBASE_HADOOP_VERSION}-bin.tar.gz.sha512"

COPY --chmod=777 start.sh /opt/start.sh

ENTRYPOINT [ "/opt/start.sh" ]

WORKDIR $HBASE_HOME

RUN apt-get update && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

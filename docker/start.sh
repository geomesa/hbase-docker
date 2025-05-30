#!/bin/bash

# exit on command error
set -e

UNO_HOME="${UNO_HOME:-/opt/fluo-uno}"
UNO_HOST="${UNO_HOST:-$(hostname -s)}" # use hostname if not specified

if ! pgrep -x sshd &>/dev/null; then
  /usr/sbin/sshd
fi

SECONDS=0
while true; do
  if ssh-keyscan localhost 2>&1 | grep -q OpenSSH; then
    echo "ssh is up"
    break
  fi
  if [ "$SECONDS" -gt 20 ]; then
    echo "FAILED: ssh failed to come up after 20 secs"
    exit 1
  fi
  echo "waiting for ssh to come up"
  sleep 1
  ((SECONDS+=1))
done

(
ssh-keyscan localhost        || :
ssh-keyscan 0.0.0.0          || :
ssh-keyscan "$UNO_HOST"      || :
ssh-keyscan "$(hostname -f)" || :
) 2>/dev/null >> /root/.ssh/known_hosts

# sets a hadoop config value - assumes that the value does not already exist
# params: <config file name> <config property> <config value>
function setHadoopConf() {
  echo "Setting $2 to $3"
  sed -i '/<\/configuration>/d' "$UNO_HOME/install/hadoop/etc/hadoop/$1"
  {
    echo "  <property>"
    echo "    <name>$2</name>"
    echo "    <value>$3</value>"
    echo "  </property>"
    echo "</configuration>"
    echo ""
  } >> "$UNO_HOME/install/hadoop/etc/hadoop/$1"
}

if [[ -n "$NAMENODE_PORT" ]] && [[ $NAMENODE_PORT != "8020" ]]; then
  setHadoopConf hdfs-site.xml dfs.namenode.rpc-address "$UNO_HOST:$NAMENODE_PORT"
  echo "Setting fs.defaultFS to $UNO_HOST:$NAMENODE_PORT"
  sed -i "s/REPLACE_HOST:8020/$UNO_HOST:$NAMENODE_PORT/" "$UNO_HOME/install/hadoop/etc/hadoop/core-site.xml"
fi

if [[ -n "$ZOOKEEPER_PORT" ]] && [[ $ZOOKEEPER_PORT != "2181" ]]; then
  echo "Setting zookeeper port to $ZOOKEEPER_PORT"
  sed -i "s/2181/$ZOOKEEPER_PORT/g" "$UNO_HOME"/install/zookeeper/conf/zoo.cfg
fi

if [[ -n "$HBASE_REGIONSERVER_PORT" ]] && [[ $HBASE_REGIONSERVER_PORT != "16020" ]]; then
  echo "Setting regionserver port to $HBASE_REGIONSERVER_PORT"
  sed -i "s/16020/$HBASE_REGIONSERVER_PORT/g" "$HBASE_HOME"/bin/regionservers.sh
fi

echo "Setting master port to ${HBASE_MASTER_PORT:-16000}"

cat >"$HBASE_HOME"/conf/hbase-site.xml <<EOF
<configuration>
  <property>
    <name>hbase.cluster.distributed</name>
    <value>true</value>
  </property>
  <property>
    <name>hbase.rootdir</name>
    <value>hdfs://${UNO_HOST}:${NAMENODE_PORT:-8020}/hbase</value>
  </property>
  <property>
    <name>hbase.zookeeper.quorum</name>
    <value>${UNO_HOST}:${ZOOKEEPER_PORT:-2181}</value>
  </property>
  <property>
    <name>hbase.master.port</name>
    <value>${HBASE_MASTER_PORT:-16000}</value>
  </property>
  <property>
    <name>hbase.regionserver.port</name>
    <value>${HBASE_REGIONSERVER_PORT:-16020}</value>
  </property>
  <property>
    <name>dfs.client.use.datanode.hostname</name>
    <value>true</value>
  </property>
EOF

if [[ "$HBASE_SECURITY_ENABLED" = "true" ]]; then
  echo "Setting hbase.security.authorization to true"
  cat >>"$HBASE_HOME"/conf/hbase-site.xml <<EOF
  <property>
    <name>hbase.security.authorization</name>
    <value>true</value>
  </property>
  <property>
    <name>hbase.superuser</name>
    <value>root,hbase,admin</value>
  </property>
  <property>
    <name>hbase.coprocessor.master.classes</name>
    <value>org.apache.hadoop.hbase.security.visibility.VisibilityController</value>
  </property>
  <property>
    <name>hbase.coprocessor.region.classes</name>
    <value>org.apache.hadoop.hbase.security.visibility.VisibilityController</value>
  </property>
EOF
fi

echo "</configuration>" >>"$HBASE_HOME"/conf/hbase-site.xml

# make everything in hdfs world-writable for easier development
setHadoopConf hdfs-site.xml dfs.permissions.enabled false
# use hostname for client connections so that docker network resolves correctly - note that this needs to be set on the client...
setHadoopConf hdfs-site.xml dfs.client.use.datanode.hostname true

echo "Setting host to $UNO_HOST"
grep -rl REPLACE_HOST "$UNO_HOME"/install/ | xargs sed -i "s/REPLACE_HOST/$UNO_HOST/g"

# execute uno commands in a subshell so that we don't pick up the classpath changes
(
  # shellcheck disable=SC1090
  source <("$UNO_HOME"/bin/uno env)
  if ! "$UNO_HOME"/bin/uno run zookeeper; then
    cat "$UNO_HOME"/install/logs/setup/*
    exit 1
  fi
  if ! "$UNO_HOME"/bin/uno run hadoop; then
    cat "$UNO_HOME"/install/logs/setup/*
    exit 1
  fi
)

if [[ $? -ne 0 ]]; then
  echo "Error starting hadoop:"
  cat "$UNO_HOME"/install/logs/hadoop/*.log
  exit 1
fi

echo "Running HBase..."
if ! "$HBASE_HOME"/bin/start-hbase.sh; then
  cat "$HBASE_HOME"/logs/*
  exit 1
fi

i=0
while ! grep -q "Master has completed initialization" "$HBASE_HOME"/logs/*; do
  if [[ $i -ge 60 ]]; then
    echo "Error starting HBase:"
    cat "$HBASE_HOME"/logs/*
    exit 1
  fi
  i=$((i + 1))
  echo -n "."
  sleep 1
done
echo -e "\nHBase startup complete"

# handle stopping on kill signal
_stop() {
  echo "Shutting down..."
  kill "$child" 2>/dev/null
#  "$UNO_HOME"/bin/uno stop hadoop
#  "$UNO_HOME"/bin/uno stop zookeeper
#  "$HBASE_HOME"/bin/stop-hbase.sh
}

trap _stop TERM INT
tail -f /dev/null "$HBASE_HOME"/logs/* &
child=$!
wait "$child"

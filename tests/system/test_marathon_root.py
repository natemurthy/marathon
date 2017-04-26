""" Test using root marathon.
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to root marathon, specifically
    tests round dcos services registration and control and security.
"""
import common
import os


# this is intentional import *
# it imports all the common test_ methods which are to be tested on root and mom
from dcos_service_marathon_tests import *
from marathon_common_tests import *
from marathon_pods_tests import *
from shakedown import (masters, required_masters, public_agents, required_public_agents)
from datetime import timedelta

pytestmark = [pytest.mark.usefixtures('marathon_service_name')]


@pytest.fixture(scope="function")
def marathon_service_name():
    yield 'marathon'
    clear_marathon()


def setup_module(module):
    common.cluster_info()
    clear_marathon()


def teardown_module(module):
    clear_marathon()

##################
# Root specific tests
##################


@masters(3)
def test_marathon_delete_leader(marathon_service_name):

    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))
    common.delete_marathon_path('v2/leader')

    common.wait_for_marathon_up()

    @retrying.retry(stop_max_attempt_number=30)
    def marathon_leadership_changed():
        current_leader = shakedown.marathon_leader_ip()
        print('leader: {}'.format(current_leader))
        assert original_leader != current_leader

    marathon_leadership_changed()


@masters(3)
def test_marathon_zk_partition_leader_change(marathon_service_name):

    original_leader = common.get_marathon_leader_not_on_master_leader_node()

    # blocking zk on marathon leader (not master leader)
    with shakedown.iptable_rules(original_leader):
        block_port(original_leader, 2181, direction='INPUT')
        block_port(original_leader, 2181, direction='OUTPUT')
        #  time of the zk block
        time.sleep(5)

    common.wait_for_marathon_up()

    current_leader = shakedown.marathon_leader_ip()
    assert original_leader != current_leader


@masters(3)
def test_marathon_master_partition_leader_change(marathon_service_name):

    original_leader = common.get_marathon_leader_not_on_master_leader_node()

    # blocking outbound connection to mesos master
    with shakedown.iptable_rules(original_leader):
        block_port(original_leader, 5050, direction='OUTPUT')
        #  time of the master block
        time.sleep(timedelta(minutes=1.5).total_seconds())

    common.wait_for_marathon_up()

    current_leader = shakedown.marathon_leader_ip()
    assert original_leader != current_leader


@public_agents(1)
def test_launch_app_on_public_agent():
    """ Test the successful launch of a mesos container on public agent.
        MoMs by default do not have slave_public access.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    app_def = common.add_role_constraint_to_app_def(app_mesos(app_id).copy(), ['slave_public'])
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    task_ip = tasks[0]['host']

    assert task_ip in shakedown.get_public_agents()


def test_external_volume():
    volume_name = "marathon-si-test-vol-{}".format(uuid.uuid4().hex)
    app_def = common.external_volume_mesos_app(volume_name)
    app_id = app_def['id']

    # Tested with root marathon since MoM doesn't have
    # --enable_features external_volumes option activated.
    # First deployment should create the volume since it has a unique name
    try:
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        # Create the app: the volume should be successfully created
        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Scale down to 0
        client.stop_app(app_id)
        shakedown.deployment_wait()

        # Scale up again: the volume should be successfully reused
        client.scale_app(app_id, 1)
        shakedown.deployment_wait()

        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Remove the app to be able to remove the volume
        client.remove_app(app_id)
        shakedown.deployment_wait()
    except Exception as e:
        print('Fail to test external volumes: {}'.format(e))
        raise e
    finally:
        # Clean up after the test: external volumes are not destroyed by marathon or dcos
        # and have to be cleaned manually.
        agent = shakedown.get_private_agents()[0]
        result, output = shakedown.run_command_on_agent(agent, 'sudo /opt/mesosphere/bin/dvdcli remove --volumedriver=rexray --volumename={}'.format(volume_name))  # NOQA
        # Note: Removing the volume might fail sometimes because EC2 takes some time (~10min) to recognize that
        # the volume is not in use anymore hence preventing it's removal. This is a known pitfall: we log the error
        # and the volume should be cleaned up manually later.
        if not result:
            print('WARNING: Failed to remove external volume with name={}: {}'.format(volume_name, output))


    @pytest.mark.skip(reason="Not yet implemented in mesos")
    def test_app_secret_volume():
        # Install enterprise-cli since it's needed to create secrets
        if not is_enterprise_cli_package_installed():
            install_enterprise_cli_package()

        # Create the secret
        secret_name = '/path/to/secret'
        secret_value = 'super_secret_password'
        common.create_secret(secret_name, secret_value)

        app_id = '{}/{}'.format(secret_name, uuid.uuid4().hex)
        app_def = {
            "id": app_id,
            "instances": 1,
            "cpus": 0.1,
            "mem": 64,
            "cmd": "/opt/mesosphere/bin/python -m http.server $PORT_API",
            "container": {
                "type": "MESOS",
                "volumes": [{
                    "secret": {
                        "source": secret_name
                    }
                }]
            },
            "portDefinitions": [{
                "port": 0,
                "protocol": "tcp",
                "name": "api",
                "labels": {}
            }]
        }

        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        # The secret by default is saved in $MESOS_SANDBOX/.secrets/path/to/secret
        cmd = "curl {}:{}/.secrets{}".format(host, port, secret_name)
        run, data = shakedown.run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == secret_value

        # TODO: use fixture to clean-up the secret


    def test_app_secret_env_var():
        # Install enterprise-cli since it's needed to create secrets
        if not is_enterprise_cli_package_installed():
            install_enterprise_cli_package()

        # Create the secret
        secret_name = '/path/to/secret'
        secret_value = 'super_secret_password'
        common.create_secret(secret_name, secret_value)

        app_id = '{}/{}'.format(secret_name, uuid.uuid4().hex)
        app_def = {
            "id": app_id,
            "instances": 1,
            "cpus": 0.1,
            "mem": 64,
            "cmd": "echo $SECRET_ENV >> $MESOS_SANDBOX/secret/env && /opt/mesosphere/bin/python -m http.server $PORT_API",
            "env": {
                "SECRET_ENV": {
                    "secret": {
                        "source": secret_name
                    }
                }
            },
            "portDefinitions": [{
                "port": 0,
                "protocol": "tcp",
                "name": "api",
                "labels": {}
            }]
        }

        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        cmd = "curl {}:{}/secret/env".format(host, port)
        run, data = shakedown.run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == secret_value

        # TODO: use fixture to clean-up the secret

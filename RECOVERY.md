# Recovery

## Intro

Since version 0.3.2 eclair supports a recovery procedure, this is an attempt to recover your channel
funds in the eventuality that eclair's `datadir` is lost or corrupted. The recovery assumes that eclair was running 
along with bitcoin-core and you have backed up your on-chain wallet, attempting to recover funds without the original
on-chain wallet will fail, please refer to https://bitcoin.org/en/secure-your-wallet#backup to backup your bitcoin 
wallet file. Backups are per-channel and static, this means once you got a channel backup you don't have to update 
it anymore. Note that https://github.com/ACINQ/eclair#backup has a different scope and will allow you to recover all
funds in all the channels even if the remote peer is missing, however it does require constant update.


### Expectations

The recovery won't be able to restore in-flight HTLCs but it has a good chance to recover your main output of the channel.
For the recovery to work the remote peer must be online, reachable and cooperative, also it must support `option_data_loss_protect`.


### Prerequisites

- Bitcoin core up and running with the backup.
- Seed.dat and channel backup available.
- Knowledge of the channel short-id, or transactionId of the funding.
- Remote node is online and reachable.
- Remote node supports `option_data_loss_protect`

### How to obtain a channel backup

To back up a certain channel you must start eclair with the API enabled, please refer to [this procedure](https://github.com/ACINQ/eclair#configuring-eclair) 
to configure eclair properly. Once running you need to call the `/backup` API endpoint and specify the target channel
with the according parameters, the response is the actual backup and it looks like: `0004dcca8d9295baa71b6cdeea792850133a`. 


### Recovery attempt

Assuming you have bitcoin-core available and synced with the same wallet you were using when it was backed up, then 
you need to place your eclair `seed.dat` in the eclair's `datadir`. There are two ways to attempt the recovery, interactive
where via console you're asked for the data necessary for the recovery, or via API calling `/recovery` and supplying the data
there. For this guide we'll use the interactive procedure.


1. Start eclair with the command-line option `-Declair.recoveryMode=true`
2. Insert the `URI` of the target node
3. Insert the channel backup
4. Insert the shortChannelId, in the official format HEIGHTxTX_INDEXxOUT_INDEX
5. Press enter and look at the logs.

If the recovery procedure was successful the remote node will broadcast its latest version of the commitment transaction,
and eclair is able to immediately spend your output back to your on-chain wallet.
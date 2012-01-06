/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 * Author: chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.blockstorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.Dispatcher;
import com.eucalyptus.component.NoSuchComponentException;
import com.eucalyptus.component.NoSuchServiceException;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.id.Storage;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.vm.VmInstance;
import com.eucalyptus.vm.VmInstances;
import com.eucalyptus.ws.client.ServiceDispatcher;
import com.google.common.base.Function;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import edu.ucsb.eucalyptus.msgs.AttachedVolume;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import edu.ucsb.eucalyptus.msgs.DescribeStorageVolumesResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeStorageVolumesType;
import edu.ucsb.eucalyptus.msgs.StorageVolume;

public class StorageUtil {
  private static Logger LOG = Logger.getLogger( StorageUtil.class );
  
  public static ArrayList<edu.ucsb.eucalyptus.msgs.Volume> getVolumeReply( List<Volume> volumes ) throws EucalyptusCloudException {
    Multimap<String, Volume> partitionVolumeMap = HashMultimap.create( );
    Map<String, StorageVolume> idStorageVolumeMap = Maps.newHashMap( );
    for ( Volume v : volumes ) {
      partitionVolumeMap.put( v.getPartition( ), v );
    }
    ArrayList<edu.ucsb.eucalyptus.msgs.Volume> reply = Lists.newArrayList( );
    for ( String partition : partitionVolumeMap.keySet( ) ) {
      try {
        ServiceConfiguration scConfig = Topology.lookup( Storage.class, Partitions.lookupByName( partition ) );
        Iterator<String> volumeNames = Iterators.transform( partitionVolumeMap.get( partition ).iterator( ), new Function<Volume, String>( ) {
          @Override
          public String apply( Volume arg0 ) {
            return arg0.getDisplayName( );
          }
        } );
        DescribeStorageVolumesType descVols = new DescribeStorageVolumesType( Lists.newArrayList( volumeNames ) );
        Dispatcher sc = ServiceDispatcher.lookup( scConfig );
        DescribeStorageVolumesResponseType volState = sc.send( descVols );
        for ( StorageVolume vol : volState.getVolumeSet( ) ) {
          idStorageVolumeMap.put( vol.getVolumeId( ), vol );
        }
        for ( Volume v : volumes ) {
          if ( !partition.equals( v.getPartition( ) ) ) continue;
          String status = null;
          Integer size = 0;
          String actualDeviceName = "unknown";
          if ( idStorageVolumeMap.containsKey( v.getDisplayName( ) ) ) {
            StorageVolume vol = idStorageVolumeMap.get( v.getDisplayName( ) );
            status = vol.getStatus( );
            size = Integer.parseInt( vol.getSize( ) );
            actualDeviceName = vol.getActualDeviceName( );
          } else {
            v.setState( State.ANNIHILATED );
          }
          AttachedVolume vmAttachedVol = null;
          try {
            VmInstance vm = VmInstances.lookupByVolumeId( v.getDisplayName( ) );
            vmAttachedVol = vm.lookupVolumeAttachment( v.getDisplayName( ) );
          } catch ( Exception ex ) {
            LOG.error( ex, ex );
          }
          if ( vmAttachedVol != null ) {
            v.setState( State.BUSY );
          } else if ( status != null ) {
            v.setMappedState( status );
          }
          if ( v.getSize( ) <= 0 ) {
            v.setSize( new Integer( size ) );
          }
          if ( "invalid".equals( v.getRemoteDevice( ) ) || "unknown".equals( v.getRemoteDevice( ) ) || v.getRemoteDevice( ) == null ) {
            v.setRemoteDevice( actualDeviceName );
          }
          edu.ucsb.eucalyptus.msgs.Volume aVolume = v.morph( new edu.ucsb.eucalyptus.msgs.Volume( ) );
          if ( vmAttachedVol != null ) {
            aVolume.setStatus( v.mapState( ) );
            aVolume.getAttachmentSet( ).add( vmAttachedVol );
            for ( AttachedVolume attachedVolume : aVolume.getAttachmentSet( ) ) {
              if ( !attachedVolume.getDevice( ).startsWith( "/dev/" ) ) {
                attachedVolume.setDevice( "/dev/" + attachedVolume.getDevice( ) );
              }
            }
            
          }
          if ( "invalid".equals( v.getRemoteDevice( ) ) && !State.FAIL.equals( v.getState( ) ) ) {
            aVolume.setStatus( "creating" );
          }
          reply.add( aVolume );
        }
      } catch ( NoSuchElementException ex ) {
        LOG.error( ex, ex );
      } catch ( NumberFormatException ex ) {
        LOG.error( ex, ex );
      }
    }
    return reply;
  }
  
}

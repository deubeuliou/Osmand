package net.osmand.binary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import net.osmand.StringMatcher;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.OsmandOdb.OsmAndAddressIndex.CitiesIndex;
import net.osmand.data.Building;
import net.osmand.data.Building.BuildingInterpolation;
import net.osmand.data.City;
import net.osmand.data.City.CityType;
import net.osmand.data.Street;
import net.osmand.osm.LatLon;
import net.osmand.osm.MapUtils;
import net.sf.junidecode.Junidecode;

import com.google.protobuf.CodedInputStreamRAF;
import com.google.protobuf.WireFormat;

public class BinaryMapAddressReaderAdapter {
	
	public final static int CITY_TOWN_TYPE = 1;
	public final static int POSTCODES_TYPE = 2;
	public final static int VILLAGES_TYPE = 3;
	public final static int STREET_TYPE = 4;
	
	public static class AddressRegion extends BinaryIndexPart {
		String enName;
		int indexNameOffset = -1;
		List<CitiesBlock> cities = new ArrayList<BinaryMapAddressReaderAdapter.CitiesBlock>();
		LatLon calculatedCenter = null;
	}
	
	public static class CitiesBlock extends BinaryIndexPart {
		int type;
	}
	
	private CodedInputStreamRAF codedIS;
	private final BinaryMapIndexReader map;
	
	protected BinaryMapAddressReaderAdapter(BinaryMapIndexReader map){
		this.codedIS = map.codedIS;
		this.map = map;
	}

	private void skipUnknownField(int t) throws IOException {
		map.skipUnknownField(t);
	}
	
	private int readInt() throws IOException {
		return map.readInt();
	}
	

	protected void readAddressIndex(AddressRegion region) throws IOException {
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				if(region.enName == null || region.enName.length() == 0){
					region.enName = Junidecode.unidecode(region.name);
				}
				return;
			case OsmandOdb.OsmAndAddressIndex.NAME_FIELD_NUMBER :
				region.name = codedIS.readString();
				break;
			case OsmandOdb.OsmAndAddressIndex.NAME_EN_FIELD_NUMBER :
				region.enName = codedIS.readString();
				break;
			case OsmandOdb.OsmAndAddressIndex.CITIES_FIELD_NUMBER :
				CitiesBlock block = new CitiesBlock();
				region.cities.add(block);
				block.type = 1; 
				block.length  = readInt();
				block.filePointer = codedIS.getTotalBytesRead();
				while(true){
					int tt = codedIS.readTag();
					int ttag = WireFormat.getTagFieldNumber(tt);
					if(ttag == 0) {
						break;
					} else if(ttag == CitiesIndex.TYPE_FIELD_NUMBER){
						block.type = codedIS.readUInt32();
						break;
					} else {
						skipUnknownField(tt);
					}
				}
				
				
				codedIS.seek(block.filePointer + block.length);
				
				break;
			case OsmandOdb.OsmAndAddressIndex.NAMEINDEX_FIELD_NUMBER :
				region.indexNameOffset = codedIS.getTotalBytesRead();
				int length = readInt();
				codedIS.seek(region.indexNameOffset + length + 4);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	protected void readCities(List<City> cities, SearchRequest<City> resultMatcher, StringMatcher matcher, boolean useEn) throws IOException {
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				return;
			case CitiesIndex.CITIES_FIELD_NUMBER :
				int fp = codedIS.getTotalBytesRead();
				int length = codedIS.readRawVarint32();
				int oldLimit = codedIS.pushLimit(length);
				City c = readCityHeader(matcher, fp, useEn);
				if(c != null){
					if (resultMatcher == null || resultMatcher.publish(c)) {
						cities.add(c);
					}
				}
				codedIS.popLimit(oldLimit);
				if(resultMatcher != null && resultMatcher.isCancelled()){
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
				}
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	
	protected void readCityStreets(SearchRequest<Street> resultMatcher, City city) throws IOException{
		int x = MapUtils.get31TileNumberX(city.getLocation().getLongitude());
		int y = MapUtils.get31TileNumberY(city.getLocation().getLatitude());
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				return;
			case OsmandOdb.CityBlockIndex.STREETS_FIELD_NUMBER :
				Street s = new Street(city);
				s.setFileOffset(codedIS.getTotalBytesRead());
				int length = codedIS.readRawVarint32();
				int oldLimit = codedIS.pushLimit(length);				
				readStreet(s, null, false, x >> 7, y >> 7, city.isPostcode() ? city.getName() : null);
				if(resultMatcher == null || resultMatcher.publish(s)){
					city.registerStreet(s);
				}
				if(resultMatcher != null && resultMatcher.isCancelled()) {
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
				}
				codedIS.popLimit(oldLimit);
				break;
			case OsmandOdb.CityBlockIndex.BUILDINGS_FIELD_NUMBER :
				// buildings for the town are not used now
				skipUnknownField(t);
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	protected City readCityHeader(StringMatcher nameMatcher, int filePointer, boolean useEn) throws IOException{
		int x = 0;
		int y = 0;
		City c = null;
		boolean englishNameMatched = false;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				return c;
			case OsmandOdb.CityIndex.CITY_TYPE_FIELD_NUMBER :
				int type = codedIS.readUInt32();
				c = new City(CityType.values()[type]);
				break;
			case OsmandOdb.CityIndex.ID_FIELD_NUMBER :
				c.setId(codedIS.readUInt64());
				if(nameMatcher != null && useEn && !englishNameMatched){
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
					return null;
				}
				break;
			case OsmandOdb.CityIndex.NAME_EN_FIELD_NUMBER :
				String enName = codedIS.readString();
				if (nameMatcher != null && enName.length() > 0) {
					if(!nameMatcher.matches(enName)){
						codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
						return null;
					} else {
						englishNameMatched = true;
					}
				}
				c.setEnName(enName);
				break;
			case OsmandOdb.CityIndex.NAME_FIELD_NUMBER :
				String name = codedIS.readString();
				if(nameMatcher != null){
					if(!useEn){
						if(!nameMatcher.matches(name)) {
							codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
							return null;
						}
					} else {
						if(nameMatcher.matches(Junidecode.unidecode(name))){
							englishNameMatched = true;
						}
					}
				}
				if(c == null) {
					c = City.createPostcode(name); 
				}
				c.setName(name);
				break;
			case OsmandOdb.CityIndex.X_FIELD_NUMBER :
				x = codedIS.readUInt32();
				break;
			case OsmandOdb.CityIndex.Y_FIELD_NUMBER :
				y = codedIS.readUInt32();
				c.setLocation(MapUtils.get31LatitudeY(y), MapUtils.get31LongitudeX(x));
				if(c.getEnName().length() == 0){
					c.setEnName(Junidecode.unidecode(c.getName()));
				}
				break;
			case OsmandOdb.CityIndex.SHIFTTOCITYBLOCKINDEX_FIELD_NUMBER :
				int offset = readInt();
				offset += filePointer;
				c.setFileOffset(offset);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	protected Street readStreet(Street s, SearchRequest<Building> buildingsMatcher, boolean loadBuildingsAndIntersected, int city24X, int city24Y, String postcodeFilter) throws IOException{
		int x = 0;
		int y = 0;
		boolean loadLocation = city24X != 0 || city24Y != 0;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				if(loadLocation){
					s.setLocation(MapUtils.getLatitudeFromTile(24, y), MapUtils.getLongitudeFromTile(24, x));
				}
				if(s.getEnName().length() == 0){
					s.setEnName(Junidecode.unidecode(s.getName()));
				}
				return s;
			case OsmandOdb.StreetIndex.ID_FIELD_NUMBER :
				s.setId(codedIS.readUInt64());
				break;
			case OsmandOdb.StreetIndex.NAME_EN_FIELD_NUMBER :
				s.setEnName(codedIS.readString());
				break;
			case OsmandOdb.StreetIndex.NAME_FIELD_NUMBER :
				s.setName(codedIS.readString());
				break;
			case OsmandOdb.StreetIndex.X_FIELD_NUMBER :
				int sx = codedIS.readSInt32();
				if(loadLocation){
					x =  sx + city24X;
				} else {
					x = (int) MapUtils.getTileNumberX(24, s.getLocation().getLongitude());
				}
				break;
			case OsmandOdb.StreetIndex.Y_FIELD_NUMBER :
				int sy = codedIS.readSInt32();
				if(loadLocation){
					y =  sy + city24Y;
				} else {
					y = (int) MapUtils.getTileNumberY(24, s.getLocation().getLatitude());
				}
				break;
			case OsmandOdb.StreetIndex.INTERSECTIONS_FIELD_NUMBER :
				int length = codedIS.readRawVarint32();
				if(loadBuildingsAndIntersected){
					int oldLimit = codedIS.pushLimit(length);
					Street si = readIntersectedStreet(s.getCity(), x, y);
					s.addIntersectedStreet(si);
					codedIS.popLimit(oldLimit);
				} else {
					codedIS.skipRawBytes(length);
				}
				break;
			case OsmandOdb.StreetIndex.BUILDINGS_FIELD_NUMBER :
				int offset = codedIS.getTotalBytesRead();
				length = codedIS.readRawVarint32();
				if(loadBuildingsAndIntersected){
					int oldLimit = codedIS.pushLimit(length);
					Building b = readBuilding(offset, x, y);
					if (postcodeFilter == null || postcodeFilter.equalsIgnoreCase(b.getPostcode())) {
						if (buildingsMatcher == null || buildingsMatcher.publish(b)) {
							s.registerBuilding(b);
						}
					}
					codedIS.popLimit(oldLimit);
				} else {
					codedIS.skipRawBytes(length);
				}
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	protected Street readIntersectedStreet(City c, int street24X, int street24Y) throws IOException{
		int x = 0;
		int y = 0;
		Street s = new Street(c);
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				s.setLocation(MapUtils.getLatitudeFromTile(24, y), MapUtils.getLongitudeFromTile(24, x));
				if(s.getEnName().length() == 0){
					s.setEnName(Junidecode.unidecode(s.getName()));
				}
				return s;
			case OsmandOdb.BuildingIndex.ID_FIELD_NUMBER :
				s.setId(codedIS.readUInt64());
				break;
			case OsmandOdb.StreetIntersection.NAME_EN_FIELD_NUMBER:
				s.setEnName(codedIS.readString());
				break;
			case OsmandOdb.StreetIntersection.NAME_FIELD_NUMBER:
				s.setName(codedIS.readString());
				break;
			case OsmandOdb.StreetIntersection.INTERSECTEDX_FIELD_NUMBER :
				x =  codedIS.readSInt32() + street24X;
				break;
			case OsmandOdb.StreetIntersection.INTERSECTEDY_FIELD_NUMBER :
				y =  codedIS.readSInt32() + street24Y;
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	protected Building readBuilding(int fileOffset, int street24X, int street24Y) throws IOException{
		int x = 0;
		int y = 0;
		Building b = new Building();
		b.setFileOffset(fileOffset);
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				b.setLocation(MapUtils.getLatitudeFromTile(24, y), MapUtils.getLongitudeFromTile(24, x));
				if(b.getEnName().length() == 0){
					b.setEnName(Junidecode.unidecode(b.getName()));
				}
				return b;
			case OsmandOdb.BuildingIndex.ID_FIELD_NUMBER :
				b.setId(codedIS.readUInt64());
				break;
			case OsmandOdb.BuildingIndex.NAME_EN_FIELD_NUMBER :
				b.setEnName(codedIS.readString());
				break;
			case OsmandOdb.BuildingIndex.NAME_FIELD_NUMBER :
				b.setName(codedIS.readString());
				break;
			case OsmandOdb.BuildingIndex.NAME_EN2_FIELD_NUMBER :
				// no where to set now
				codedIS.readString();
				break;
			case OsmandOdb.BuildingIndex.NAME2_FIELD_NUMBER :
				b.setName2(codedIS.readString());
				break;
			case OsmandOdb.BuildingIndex.INTERPOLATION_FIELD_NUMBER :
				int sint = codedIS.readSInt32();
				if(sint > 0) {
					b.setInterpolationInterval(sint);
				} else {
					b.setInterpolationType(BuildingInterpolation.fromValue(sint));
				}
				break;
			case OsmandOdb.BuildingIndex.X_FIELD_NUMBER :
				x =  codedIS.readSInt32() + street24X;
				break;
			case OsmandOdb.BuildingIndex.Y_FIELD_NUMBER :
				y =  codedIS.readSInt32() + street24Y;
				break;
			case OsmandOdb.BuildingIndex.POSTCODE_FIELD_NUMBER :
				b.setPostcode(codedIS.readString());
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	
}

/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.query.parsing.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.hql.ast.spi.EntityNamesResolver;
import org.hibernate.hql.ast.spi.PropertyHelper;
import org.hibernate.ogm.model.spi.Tuple;
import org.hibernate.ogm.persister.impl.OgmCollectionPersister;
import org.hibernate.ogm.persister.impl.OgmEntityPersister;
import org.hibernate.ogm.type.spi.GridType;
import org.hibernate.ogm.type.spi.TypeTranslator;
import org.hibernate.ogm.util.impl.StringHelper;
import org.hibernate.persister.entity.Joinable;
import org.hibernate.type.AbstractStandardBasicType;
import org.hibernate.type.AssociationType;
import org.hibernate.type.ComponentType;
import org.hibernate.type.Type;

/**
 * {@link PropertyHelper} implementation containing common methods to obtain the type of the properties or the
 * column names they are mapped to.
 *
 * @author Davide D'Alto
 */
public class ParserPropertyHelper implements PropertyHelper {

	private final SessionFactoryImplementor sessionFactory;
	private final EntityNamesResolver entityNames;

	public ParserPropertyHelper(SessionFactoryImplementor sessionFactory, EntityNamesResolver entityNames) {
		this.sessionFactory = sessionFactory;
		this.entityNames = entityNames;
	}

	@Override
	public Object convertToPropertyType(String entityType, List<String> propertyPath, String value) {
		Type propertyType = getPropertyType( entityType, propertyPath );

		if ( propertyType.isEntityType() ) {
			throw new UnsupportedOperationException( "Queries on associated entities are not supported yet." );
		}

		if ( propertyType instanceof AbstractStandardBasicType ) {
			return ( (AbstractStandardBasicType<?>) propertyType ).fromString( value );
		}
		else {
			return value;
		}
	}

	@Override
	public Object convertToBackendType(String entityType, List<String> propertyPath, Object value) {
		Type propertyType = getPropertyType( entityType, propertyPath );
		GridType ogmType = sessionFactory.getServiceRegistry().getService( TypeTranslator.class ).getType( propertyType );
		Tuple tuple = new Tuple();

		ogmType.nullSafeSet( tuple, value, new String[] { "dummy" }, null );

		return tuple.get( "dummy" );
	}

	protected Type getPropertyType(String entityType, List<String> propertyPath) {
		Iterator<String> pathIterator = propertyPath.iterator();
		OgmEntityPersister persister = getPersister( entityType );
		String propertyName = pathIterator.next();
		Type propertyType = persister.getPropertyType( propertyName );
		if ( !pathIterator.hasNext() ) {
			return propertyType;
		}
		else if ( propertyType.isComponentType() ) {
			// Embeddded property
			return getComponentPropertyType( propertyType, pathIterator );
		}
		else if ( propertyType.isAssociationType() ) {
			Joinable associatedJoinable = ( (AssociationType) propertyType ).getAssociatedJoinable( persister.getFactory() );
			if ( associatedJoinable.isCollection() ) {
				OgmCollectionPersister collectionPersister = (OgmCollectionPersister) associatedJoinable;
				if ( collectionPersister.getType().isComponentType() ) {
					// Collection of embeddables
					return getComponentPropertyType( collectionPersister.getType(), pathIterator );
				}
			}
		}
		throw new UnsupportedOperationException( "Unrecognized property type: " + propertyType );
	}

	private Type getComponentPropertyType(Type type, Iterator<String> pathIterator) {
		if ( pathIterator.hasNext() ) {
			String property = pathIterator.next();
			Type subType = componentPropertyType( (ComponentType) type, property );
			if ( subType.isComponentType() ) {
				return getComponentPropertyType( subType, pathIterator );
			}
			else if ( subType.isAssociationType() ) {
				throw new UnsupportedOperationException( "Queries on collection in embeddables are not supported: " + property );
			}
			else {
				return subType;
			}
		}
		else {
			return type;
		}
	}

	private Type componentPropertyType(ComponentType componentType, String property) {
		return componentType.getSubtypes()[componentType.getPropertyIndex( property )];
	}

	protected OgmEntityPersister getPersister(String entityType) {
		Class<?> targetedType = entityNames.getClassFromName( entityType );
		if ( targetedType == null ) {
			throw new IllegalStateException( "Unknown entity name " + entityType );
		}

		return (OgmEntityPersister) sessionFactory.getEntityPersister( targetedType.getName() );
	}

	public boolean isEmbeddedProperty(String targetTypeName, List<String> namesWithoutAlias) {
		OgmEntityPersister persister = getPersister( targetTypeName );
		Type propertyType = persister.getPropertyType( namesWithoutAlias.get( 0 ) );
		if ( propertyType.isComponentType() ) {
			// Embedded
			return true;
		}
		else if ( propertyType.isAssociationType() ) {
			Joinable associatedJoinable = ( (AssociationType) propertyType ).getAssociatedJoinable( persister.getFactory() );
			if ( associatedJoinable.isCollection() ) {
				OgmCollectionPersister collectionPersister = (OgmCollectionPersister) associatedJoinable;
				return collectionPersister.getType().isComponentType();
			}
		}
		return false;
	}

	/**
	 * Check if the path to the property correspond to an association.
	 *
	 * @param targetTypeName the name of the entity containing the property
	 * @param pathWithoutAlias the path to the property WIHTOUT aliases
	 * @return {@code true} if the property is an association or {@code false} otherwise
	 */
	public boolean isAssociation(String targetTypeName, List<String> pathWithoutAlias) {
		OgmEntityPersister persister = getPersister( targetTypeName );
		Type propertyType = persister.getPropertyType( pathWithoutAlias.get( 0 ) );
		return propertyType.isAssociationType();
	}

	/**
	 * Find the path to the first association in the property path.
	 *
	 * @param targetTypeName the entity with the property
	 * @param pathWithoutAlias the path to the property WITHOUT the alias
	 * @return the path to the first association or {@code null} if there isn't an association in the property path
	 */
	public List<String> findAssociationPath(String targetTypeName, List<String> pathWithoutAlias) {
		List<String> subPath = new ArrayList<String>( pathWithoutAlias.size() );
		for ( String name : pathWithoutAlias ) {
			subPath.add( name );
			if ( isAssociation( targetTypeName, subPath ) ) {
				return subPath;
			}
		}
		return null;
	}

	/**
	 * Check if the property path is a nested property.
	 * <p>
	 * Example: [anEmbeddable, anotherEmbeddedable, propertyName]
	 *
	 * @param propertyPathWithoutAlias the path to the property WITHOUT the aliases.
	 * @return {@code true} if it is a nested property, {@code false} otherwise
	 */
	public boolean isNestedProperty(List<String> propertyPathWithoutAlias) {
		return propertyPathWithoutAlias.size() > 1;
	}

	/**
	 * Check if the property path is a simple property.
	 * <p>
	 * Example: [propertyName]
	 *
	 * @param propertyPathWithoutAlias the path to the property WITHOUT the aliases
	 * @return {@code true} if it is a simple property, {@code false} otherwise
	 */
	public boolean isSimpleProperty(List<String> propertyPathWithoutAlias) {
		return propertyPathWithoutAlias.size() == 1;
	}

	/**
	 * Returns the names of all those columns which represent a collection to be stored within the owning entity
	 * structure (element collections and/or *-to-many associations, depending on the dialect's capabilities).
	 */
	protected String getColumn(OgmEntityPersister persister, List<String> propertyPath) {
		Iterator<String> pathIterator = propertyPath.iterator();
		String propertyName = pathIterator.next();
		Type propertyType = persister.getPropertyType( propertyName );
		if ( !pathIterator.hasNext() ) {
			return persister.getPropertyColumnNames( propertyName )[0];
		}
		else if ( propertyType.isComponentType() ) {
			// Embeddded property
			String componentPropertyName = StringHelper.join( propertyPath, "." );
			return persister.getPropertyColumnNames( componentPropertyName )[0];
		}
		else if ( propertyType.isAssociationType() ) {
			Joinable associatedJoinable = ( (AssociationType) propertyType ).getAssociatedJoinable( persister.getFactory() );
			if ( associatedJoinable.isCollection() ) {
				OgmCollectionPersister collectionPersister = (OgmCollectionPersister) associatedJoinable;
				if ( collectionPersister.getType().isComponentType() ) {
					StringBuilder columnNameBuilder = new StringBuilder( propertyName );
					// Collection of embeddables
					appendComponenCollectionPath( columnNameBuilder, collectionPersister, pathIterator );
					return columnNameBuilder.toString();
				}
			}
		}
		throw new UnsupportedOperationException( "Unrecognized property type: " + propertyType );
	}

	private void appendComponenCollectionPath(StringBuilder columnNameBuilder, OgmCollectionPersister persister, Iterator<String> pathIterator) {
		if ( pathIterator.hasNext() ) {
			String property = pathIterator.next();
			Type subType = componentPropertyType( (ComponentType) persister.getType(), property );
			if ( subType.isComponentType() ) {
				property += "." + StringHelper.join( pathIterator, "." );
			}
			else if ( subType.isAssociationType() ) {
				throw new UnsupportedOperationException( "Queries on collection in embeddables are not supported: " + property );
			}
			columnNameBuilder.append( "." );
			columnNameBuilder.append( persister.toColumns( property )[0] );
		}
	}

	protected SessionFactoryImplementor getSessionFactory() {
		return sessionFactory;
	}
}

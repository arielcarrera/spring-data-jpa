/*
 * Copyright 2008-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.query;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.provider.QueryExtractor;
import org.springframework.data.repository.query.Parameters;
import org.springframework.data.repository.query.QueryCreationException;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.lang.Nullable;

/**
 * Implementation of {@link RepositoryQuery} based on {@link javax.persistence.NamedQuery}s.
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 * @author Mark Paluch
 * @author Ariel Carrera
 */
final class NamedQuery extends AbstractJpaQuery {

	private static final String CANNOT_EXTRACT_QUERY = "Your persistence provider does not support extracting the JPQL query from a "
			+ "named query thus you can't use Pageable inside your query method. Make sure you "
			+ "have a JpaDialect configured at your EntityManagerFactoryBean as this affects "
			+ "discovering the concrete persistence provider.";

	private static final Logger LOG = LoggerFactory.getLogger(NamedQuery.class);

	private final String queryName;
	private final String countQueryName;
	private final @Nullable String countProjection;
	private final QueryExtractor extractor;
	private final boolean namedCountQueryIsPresent;
	private final DeclaredQuery declaredQuery;
	private final QueryParameterSetter.QueryMetadataCache metadataCache;

	/**
	 * Creates a new {@link NamedQuery}.
	 */
	private NamedQuery(JpaQueryMethod method, EntityManager em) {
		this(method, em, null);
	}
	
	/**
	 * Creates a new {@link NamedQuery}.
	 */
	private NamedQuery(JpaQueryMethod method, EntityManager em, EntityManager emCreation) {

		super(method, em, emCreation);
		
		this.queryName = method.getNamedQueryName();
		this.countQueryName = method.getNamedCountQueryName();
		this.extractor = method.getQueryExtractor();
		this.countProjection = method.getCountQueryProjection();

		Parameters<?, ?> parameters = method.getParameters();

		if (parameters.hasSortParameter()) {
			throw new IllegalStateException(String.format("Finder method %s is backed " + "by a NamedQuery and must "
					+ "not contain a sort parameter as we cannot modify the query! Use @Query instead!", method));
		}

		this.namedCountQueryIsPresent = hasNamedQuery((emCreation != null ? emCreation : em), countQueryName);

		Query query = em.createNamedQuery(queryName);//TODO verify if em is ok here
		String queryString = extractor.extractQueryString(query);

		this.declaredQuery = DeclaredQuery.of(queryString);

		boolean weNeedToCreateCountQuery = !namedCountQueryIsPresent && method.getParameters().hasPageableParameter();
		boolean cantExtractQuery = !this.extractor.canExtractQuery();

		if (weNeedToCreateCountQuery && cantExtractQuery) {
			throw QueryCreationException.create(method, CANNOT_EXTRACT_QUERY);
		}

		if (parameters.hasPageableParameter()) {
			LOG.warn("Finder method {} is backed by a NamedQuery" + " but contains a Pageable parameter! Sorting delivered "
					+ "via this Pageable will not be applied!", method);
		}

		this.metadataCache = new QueryParameterSetter.QueryMetadataCache();
	}

	/**
	 * Returns whether the named query with the given name exists.
	 *
	 * @param em must not be {@literal null}.
	 * @param queryName must not be {@literal null}.
	 * @return
	 */
	private static boolean hasNamedQuery(EntityManager em, String queryName) {

		/*
		 * See DATAJPA-617, we have to use a dedicated em for the lookups to avoid a
		 * potential rollback of the running tx.
		 */
		EntityManager lookupEm = em.getEntityManagerFactory().createEntityManager();

		try {
			lookupEm.createNamedQuery(queryName);
			return true;
		} catch (IllegalArgumentException e) {
			LOG.debug("Did not find named query {}", queryName);
			return false;
		} finally {
			lookupEm.close();
		}
	}

	/**
	 * Looks up a named query for the given {@link org.springframework.data.repository.query.QueryMethod}.
	 *
	 * @param method must not be {@literal null}.
	 * @param em must not be {@literal null}.
	 * @return
	 */
	@Nullable
	public static RepositoryQuery lookupFrom(JpaQueryMethod method, EntityManager em) {

	    	return lookupFrom(method, em, null);
	}
	
	/**
	 * Looks up a named query for the given {@link org.springframework.data.repository.query.QueryMethod}.
	 *
	 * @param method must not be {@literal null}.
	 * @param em must not be {@literal null}.
	 * @param emCreational can be {@literal null}.
	 * @return
	 */
	@Nullable
	public static RepositoryQuery lookupFrom(JpaQueryMethod method, EntityManager em, EntityManager emCreational) {

		final String queryName = method.getNamedQueryName();

		LOG.debug("Looking up named query {}", queryName);
		EntityManager emCreation = (emCreational != null ? emCreational : em);
		
		if (!hasNamedQuery(emCreation, queryName)) {
			return null;
		}

		try {
			RepositoryQuery query = new NamedQuery(method, em, emCreation);
			LOG.debug("Found named query {}!", queryName);
			return query;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.AbstractJpaQuery#doCreateQuery(JpaParametersParameterAccessor)
	 */
	@Override
	protected Query doCreateQuery(JpaParametersParameterAccessor accessor) {

		EntityManager em = getEntityManager();

		JpaQueryMethod queryMethod = getQueryMethod();
		ResultProcessor processor = queryMethod.getResultProcessor().withDynamicProjection(accessor);

		Class<?> typeToRead = getTypeToRead(processor.getReturnedType());

		Query query = typeToRead == null //
				? em.createNamedQuery(queryName) //
				: em.createNamedQuery(queryName, typeToRead);

		QueryParameterSetter.QueryMetadata metadata = metadataCache.getMetadata(queryName, query);

		return parameterBinder.get().bindAndPrepare(query, metadata, accessor);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.AbstractJpaQuery#doCreateCountQuery(JpaParametersParameterAccessor)
	 */
	@Override
	protected TypedQuery<Long> doCreateCountQuery(JpaParametersParameterAccessor accessor) {

		EntityManager em = getEntityManager();
		TypedQuery<Long> countQuery;

		String cacheKey;
		if (namedCountQueryIsPresent) {
			cacheKey = countQueryName;
			countQuery = em.createNamedQuery(countQueryName, Long.class);

		} else {

			String countQueryString = declaredQuery.deriveCountQuery(null, countProjection).getQueryString();
			cacheKey = countQueryString;
			countQuery = em.createQuery(countQueryString, Long.class);
		}

		QueryParameterSetter.QueryMetadata metadata = metadataCache.getMetadata(cacheKey, countQuery);

		return parameterBinder.get().bind(countQuery, metadata, accessor);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.AbstractJpaQuery#getTypeToRead()
	 */
	@Override
	protected Class<?> getTypeToRead(ReturnedType returnedType) {

		if (getQueryMethod().isNativeQuery()) {

			Class<?> type = returnedType.getReturnedType();
			Class<?> domainType = returnedType.getDomainType();

			// Domain or subtype -> use return type
			if (domainType.isAssignableFrom(type)) {
				return type;
			}

			// Domain type supertype -> use domain type
			if (type.isAssignableFrom(domainType)) {
				return domainType;
			}

			// Tuples for projection interfaces or explicit SQL mappings for everything else
			return type.isInterface() ? Tuple.class : null;
		}

		return declaredQuery.hasConstructorExpression() //
				? null //
				: super.getTypeToRead(returnedType);
	}
}
